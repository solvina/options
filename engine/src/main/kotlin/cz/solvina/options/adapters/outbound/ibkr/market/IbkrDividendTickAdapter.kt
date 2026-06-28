package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrDividendTickRegistry
import cz.solvina.options.domain.features.universe.DividendDataPort
import cz.solvina.options.domain.features.universe.DividendInfo
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

private val IB_DIVIDENDS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

/**
 * Parses the IB_DIVIDENDS string "trailing12m,forward12m,nextDividendDate(yyyyMMdd),nextAmount".
 * Blank / "0" fields become null, so non-paying names (",,,") yield an empty [DividendInfo].
 */
internal fun parseIbDividends(raw: String): DividendInfo {
    val parts = raw.split(",")
    val date =
        parts
            .getOrNull(2)
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "0" }
            ?.let { runCatching { LocalDate.parse(it, IB_DIVIDENDS_DATE) }.getOrNull() }
    val amount =
        parts
            .getOrNull(3)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }
    return DividendInfo(date, amount)
}

/**
 * Fetches the IB_DIVIDENDS string for a symbol via `reqMktData(genericTickList="456")` —
 * "trailing-12m,forward-12m,nextDividendDate,nextAmount". Uses the existing market-data line, so no
 * extra entitlement. Returns null on timeout/error.
 */
@Component
class IbkrDividendTickAdapter(
    private val registry: IbkrDividendTickRegistry,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
) : DividendDataPort {
    override suspend fun fetchDividendInfo(symbol: Symbol): DividendInfo? = fetchDividendTick(symbol)?.let { parseIbDividends(it) }

    suspend fun fetchDividendTick(
        symbol: Symbol,
        timeoutMs: Long = 15_000,
    ): String? {
        val reqId = registry.nextReqId()
        val deferred = registry.register(reqId)
        return try {
            client.reqMktData(reqId, contractFactory.stockContract(symbol), "456", false, false, null)
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            logger.warn { "[$symbol] IB_DIVIDENDS tick timed out after ${timeoutMs}ms" }
            null
        } catch (e: Exception) {
            logger.warn { "[$symbol] IB_DIVIDENDS tick failed: ${e.message}" }
            null
        } finally {
            registry.remove(reqId)
            runCatching { client.cancelMktData(reqId) }
        }
    }
}
