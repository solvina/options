package cz.solvina.options.adapters.outbound.persistence.remote

import cz.solvina.options.domain.features.universe.DividendDataPort
import cz.solvina.options.domain.features.universe.DividendInfo
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Source of the prod→paper dividend relay. The paper session runs on delayed market data where the
 * IB_DIVIDENDS generic tick (456) is not available, so instead of asking IBKR it reads the dividend
 * columns the PROD engine's own DividendRefreshService maintains in its instrument_universe table.
 */
@ConfigurationProperties(prefix = "dividends.remote")
data class RemoteDividendConfig(
    /** true = replace the IBKR dividend tick with reads from the prod engine's database. */
    val enabled: Boolean = false,
    /** Prod engine's Postgres, e.g. jdbc:postgresql://<prod-tailscale-ip>:5433/options */
    val jdbcUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** One SELECT serves a whole refresh run; anything past this age triggers a reload. */
    val cacheTtlMinutes: Long = 15,
)

/** Reads all dividend rows from an open connection to the prod database. */
internal fun readRemoteDividendRows(connection: Connection): Map<String, DividendInfo> =
    connection.prepareStatement("SELECT symbol, ex_dividend_date, next_dividend_amount FROM instrument_universe").use { stmt ->
        stmt.executeQuery().use { rs ->
            buildMap {
                while (rs.next()) {
                    put(
                        rs.getString("symbol"),
                        DividendInfo(
                            exDividendDate = rs.getDate("ex_dividend_date")?.toLocalDate(),
                            amount = rs.getBigDecimal("next_dividend_amount"),
                        ),
                    )
                }
            }
        }
    }

@Component
@ConditionalOnProperty("dividends.remote.enabled", havingValue = "true")
@Primary
class RemoteDbDividendAdapter(
    private val config: RemoteDividendConfig,
) : DividendDataPort {
    private val cacheMutex = Mutex()

    @Volatile
    private var cache: Pair<Instant, Map<String, DividendInfo>>? = null

    /**
     * Null when the symbol has no row on prod (or the prod DB is unreachable); DividendRefreshService
     * then leaves the locally stored values untouched, so a prod outage never wipes dividend data.
     */
    override suspend fun fetchDividendInfo(symbol: Symbol): DividendInfo? = cachedRows()?.get(symbol.value)

    private suspend fun cachedRows(): Map<String, DividendInfo>? =
        cacheMutex.withLock {
            val fresh =
                cache?.takeIf { (loadedAt, _) ->
                    Duration.between(loadedAt, Instant.now()).toMinutes() < config.cacheTtlMinutes
                }
            fresh?.second ?: loadRows()?.also { cache = Instant.now() to it }
        }

    private suspend fun loadRows(): Map<String, DividendInfo>? =
        withContext(Dispatchers.IO) {
            runCatching {
                DriverManager.getConnection(config.jdbcUrl, config.username, config.password).use { readRemoteDividendRows(it) }
            }.onSuccess { rows ->
                logger.info { "Remote dividend load: ${rows.size} rows from prod universe" }
            }.onFailure { e ->
                logger.warn { "Remote dividend load from ${config.jdbcUrl} failed: ${e.message}" }
            }.getOrNull()
        }
}
