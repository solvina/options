package cz.solvina.options.adapters.outbound.persistence.remote

import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.Properties

private val logger = KotlinLogging.logger {}

/**
 * Source of the prod→paper dividend relay. The paper session runs on delayed market data where the
 * IB_DIVIDENDS generic tick (456) is not available, so instead of asking IBKR it reads the dividend
 * columns the PROD engine's own DividendRefreshService maintains in its instrument_universe table.
 * (Activation flag dividends.remote.enabled is read by the @ConditionalOnProperty on the adapter.)
 */
@ConfigurationProperties(prefix = "dividends.remote")
data class RemoteDividendConfig(
    /** Prod engine's Postgres, e.g. jdbc:postgresql://<prod-tailscale-ip>:5433/options */
    val jdbcUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** One SELECT serves a whole refresh run; anything past this age triggers a reload. */
    val cacheTtlMinutes: Long = 15,
    /** After a failed load, don't retry the connection for this long — a refresh run over an
     *  unreachable prod must cost one connect timeout, not one per symbol. */
    val failureBackoffMinutes: Long = 10,
    val connectTimeoutSeconds: Int = 10,
    /** CRITICAL alert once this many loads in a row have failed (~ this many days stale). */
    val alertAfterConsecutiveFailures: Int = 3,
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
    private val jdbcTemplate: JdbcTemplate,
    private val alertPort: AlertPort,
) : DividendDataPort {
    private val stateMutex = Mutex()
    private var cache: Pair<Instant, Map<String, DividendInfo>>? = null
    private var lastFailureAt: Instant? = null
    private var consecutiveFailures = 0

    /**
     * Null when the symbol has no row on prod, the prod DB is unreachable, or a recent failure is
     * still in backoff; DividendRefreshService then leaves the locally stored values untouched, so
     * the local instrument_universe stays the point of truth and a prod outage never wipes it.
     */
    override suspend fun fetchDividendInfo(symbol: Symbol): DividendInfo? = cachedRows()?.get(symbol.value)

    private suspend fun cachedRows(): Map<String, DividendInfo>? =
        stateMutex.withLock {
            cache
                ?.takeIf { (loadedAt, _) -> age(loadedAt) < config.cacheTtlMinutes }
                ?.let { return it.second }
            lastFailureAt
                ?.takeIf { age(it) < config.failureBackoffMinutes }
                ?.let { return null }
            loadRows()
        }

    private fun age(since: Instant): Long = Duration.between(since, Instant.now()).toMinutes()

    private suspend fun loadRows(): Map<String, DividendInfo>? =
        withContext(Dispatchers.IO) {
            val attemptAt = Instant.now()
            runCatching {
                connect().use { readRemoteDividendRows(it) }
            }.onSuccess { rows ->
                cache = attemptAt to rows
                lastFailureAt = null
                consecutiveFailures = 0
                recordAttempt(attemptAt, success = true, rowsLoaded = rows.size, error = null)
                logger.info { "Remote dividend load: ${rows.size} rows from prod universe" }
            }.onFailure { e ->
                lastFailureAt = attemptAt
                consecutiveFailures++
                recordAttempt(attemptAt, success = false, rowsLoaded = null, error = e.message)
                logger.warn { "Remote dividend load from ${config.jdbcUrl} failed ($consecutiveFailures in a row): ${e.message}" }
                if (consecutiveFailures == config.alertAfterConsecutiveFailures) {
                    alertPort.send(
                        AlertLevel.CRITICAL,
                        "Dividend relay from prod failing",
                        "$consecutiveFailures consecutive failed loads from ${config.jdbcUrl} " +
                            "(last error: ${e.message}).\nLocal ex-dividend data is going stale — " +
                            "bear-call dividend protection is running on old dates. Check prod DB " +
                            "reachability and the instrument_universe schema.",
                    )
                }
            }.getOrNull()
        }

    private fun connect(): Connection =
        DriverManager.getConnection(
            config.jdbcUrl,
            Properties().apply {
                setProperty("user", config.username)
                setProperty("password", config.password)
                setProperty("connectTimeout", config.connectTimeoutSeconds.toString())
                setProperty("socketTimeout", (config.connectTimeoutSeconds * 3).toString())
            },
        )

    /** Every attempt lands in dividend_sync_status (local DB) — the point of truth for relay health.
     *  Best-effort: a broken status write must not fail the load itself. */
    private fun recordAttempt(
        attemptAt: Instant,
        success: Boolean,
        rowsLoaded: Int?,
        error: String?,
    ) {
        runCatching {
            jdbcTemplate.update(
                """
                INSERT INTO dividend_sync_status (source, last_attempt_at, last_success_at, consecutive_failures, rows_loaded, last_error)
                VALUES ('prod', ?, ?, ?, ?, ?)
                ON CONFLICT (source) DO UPDATE SET
                    last_attempt_at = EXCLUDED.last_attempt_at,
                    last_success_at = COALESCE(EXCLUDED.last_success_at, dividend_sync_status.last_success_at),
                    consecutive_failures = EXCLUDED.consecutive_failures,
                    rows_loaded = COALESCE(EXCLUDED.rows_loaded, dividend_sync_status.rows_loaded),
                    last_error = EXCLUDED.last_error
                """.trimIndent(),
                java.sql.Timestamp.from(attemptAt),
                if (success) java.sql.Timestamp.from(attemptAt) else null,
                consecutiveFailures,
                rowsLoaded,
                error,
            )
        }.onFailure { e -> logger.warn { "dividend_sync_status write failed: ${e.message}" } }
    }
}
