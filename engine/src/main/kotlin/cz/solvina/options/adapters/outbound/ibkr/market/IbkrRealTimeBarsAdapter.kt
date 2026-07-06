package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.IbkrRateLimiter
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingRealTimeBarsRequest
import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.features.bars.RealTimeBar
import cz.solvina.options.domain.features.bars.RealTimeBarsPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@Component
class IbkrRealTimeBarsAdapter(
    private val registry: IbkrMarketDataRegistry,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
    private val rateLimiter: IbkrRateLimiter,
    private val alertPort: AlertPort,
    // Shared executionCoroutineScope bean: alerts must outlive the (possibly cancelled) flow.
    private val alertScope: CoroutineScope,
) : RealTimeBarsPort {
    override fun streamBars(symbol: Symbol): Flow<RealTimeBar> =
        callbackFlow {
            val reqId = registry.nextReqId()
            val contract = contractFactory.stockContract(symbol)
            // One CRITICAL alert per subscription: IBKR may repeat the error (e.g. on farm reconnect)
            // and a rejected subscription must be loud, not spammy.
            val alerted = AtomicBoolean(false)

            // Each real-time bar subscription holds one IBKR market-data line for the session.
            rateLimiter.acquireMarketDataLine()
            registry.pendingRealTimeBars[reqId] =
                PendingRealTimeBarsRequest(
                    onBar = { bar -> trySend(bar) },
                    onError = { code, msg ->
                        // A bars stream that stops (or never starts) is otherwise indistinguishable
                        // from a quiet market — the flag strategy would be blind with no signal.
                        logger.error {
                            "[${symbol.value}] REAL-TIME BARS FAILED (reqId=$reqId, code=$code): $msg — " +
                                "flag strategy receives NO bars for ${symbol.value} until this is resolved " +
                                "(real-time bars need a live data subscription; not available on delayed data)"
                        }
                        if (alerted.compareAndSet(false, true)) {
                            alertScope.launch {
                                alertPort.send(
                                    AlertLevel.CRITICAL,
                                    "Real-time bars FAILED: ${symbol.value}",
                                    "IBKR rejected the 5-sec bars subscription (code=$code): $msg\n" +
                                        "The flag strategy is BLIND on ${symbol.value} until this is resolved.",
                                )
                            }
                        }
                    },
                )

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
