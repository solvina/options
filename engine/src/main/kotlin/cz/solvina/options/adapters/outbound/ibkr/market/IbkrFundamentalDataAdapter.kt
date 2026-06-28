package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrFundamentalDataRegistry
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Fetches IBKR fundamental-data XML for a symbol via `reqFundamentalData`. Returns null on timeout or
 * error (e.g. code 430 — no Reuters fundamentals subscription) so callers degrade gracefully.
 */
@Component
class IbkrFundamentalDataAdapter(
    private val registry: IbkrFundamentalDataRegistry,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
) {
    suspend fun fetchFundamentalXml(
        symbol: Symbol,
        reportType: String,
        timeoutMs: Long = 15_000,
    ): String? {
        val reqId = registry.nextReqId()
        val deferred = registry.register(reqId)
        return try {
            client.reqFundamentalData(reqId, contractFactory.stockContract(symbol), reportType, null)
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            logger.warn { "[$symbol] fundamental data ($reportType) timed out after ${timeoutMs}ms" }
            null
        } catch (e: Exception) {
            logger.warn { "[$symbol] fundamental data ($reportType) failed: ${e.message}" }
            null
        } finally {
            registry.remove(reqId)
            runCatching { client.cancelFundamentalData(reqId) }
        }
    }
}
