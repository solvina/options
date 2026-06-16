package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.IbkrRateLimiter
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.domain.features.bars.RealTimeBar
import cz.solvina.options.domain.features.bars.RealTimeBarsPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrRealTimeBarsAdapter(
    private val registry: IbkrMarketDataRegistry,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
    private val rateLimiter: IbkrRateLimiter,
) : RealTimeBarsPort {
    override fun streamBars(symbol: Symbol): Flow<RealTimeBar> =
        callbackFlow {
            val reqId = registry.nextReqId()
            val contract = contractFactory.stockContract(symbol)

            // Each real-time bar subscription holds one IBKR market-data line for the session.
            rateLimiter.acquireMarketDataLine()
            registry.pendingRealTimeBars[reqId] = { bar -> trySend(bar) }

            logger.info { "[${symbol.value}] Subscribing to 5-sec real-time bars (reqId=$reqId, RTH only)" }
            client.reqRealTimeBars(reqId, contract, 5, "TRADES", true, null)

            awaitClose {
                registry.pendingRealTimeBars.remove(reqId)
                runCatching { client.cancelRealTimeBars(reqId) }
                    .onFailure { e -> logger.warn { "[${symbol.value}] cancelRealTimeBars failed: ${e.message}" } }
                rateLimiter.releaseMarketDataLine()
                logger.info { "[${symbol.value}] Unsubscribed from real-time bars (reqId=$reqId)" }
            }
        }.buffer(Channel.UNLIMITED)
}
