package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingContinuousMarketDataRequest
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingTickByTickRequest
import cz.solvina.options.domain.features.market.MarketTickPort
import cz.solvina.options.domain.features.market.SpreadCreditTick
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

private data class LegPrices(
    val bid: Double = Double.NaN,
    val ask: Double = Double.NaN,
)

@Component
class IbkrMarketTickAdapter(
    private val registry: IbkrMarketDataRegistry,
    private val client: EClientSocket,
) : MarketTickPort {
    override fun streamUnderlyingPrice(symbol: Symbol): Flow<Double> =
        callbackFlow {
            val reqId = registry.nextReqId()
            registry.pendingContinuousMarketData[reqId] =
                PendingContinuousMarketDataRequest(
                    onUpdate = { snapshot ->
                        val price =
                            snapshot.last.takeIf { !it.isNaN() }
                                ?: snapshot.close.takeIf { !it.isNaN() }
                        if (price != null) trySend(price)
                    },
                )
            client.reqMktData(reqId, buildStockContract(symbol), "", false, false, null)
            logger.debug { "[$symbol] Started underlying price stream (reqId=$reqId)" }
            awaitClose {
                registry.pendingContinuousMarketData.remove(reqId)
                client.cancelMktData(reqId)
                logger.debug { "[$symbol] Cancelled underlying price stream (reqId=$reqId)" }
            }
        }

    override fun streamSpreadCredit(
        soldContract: OptionContract,
        boughtContract: OptionContract,
    ): Flow<SpreadCreditTick> =
        callbackFlow {
            // reqTickByTick for real-time bid/ask on each leg
            val soldTickReqId = registry.nextReqId()
            val boughtTickReqId = registry.nextReqId()
            // reqMktData(snapshot=false) for continuous Greeks on each leg
            val soldGreeksReqId = registry.nextReqId()
            val boughtGreeksReqId = registry.nextReqId()

            val soldPrices = AtomicReference(LegPrices())
            val boughtPrices = AtomicReference(LegPrices())

            fun emitIfReady() {
                val s = soldPrices.get()
                val b = boughtPrices.get()
                if (s.bid.isNaN() || s.ask.isNaN() || b.bid.isNaN() || b.ask.isNaN()) return
                val soldMid = (s.bid + s.ask) / 2
                val boughtMid = (b.bid + b.ask) / 2
                val soldDelta =
                    registry.pendingContinuousMarketData[soldGreeksReqId]
                        ?.snapshot
                        ?.delta
                        ?.takeIf { !it.isNaN() }
                val boughtDelta =
                    registry.pendingContinuousMarketData[boughtGreeksReqId]
                        ?.snapshot
                        ?.delta
                        ?.takeIf { !it.isNaN() }
                trySend(
                    SpreadCreditTick(
                        soldBid = s.bid,
                        soldAsk = s.ask,
                        boughtBid = b.bid,
                        boughtAsk = b.ask,
                        netCredit = soldMid - boughtMid,
                        soldDelta = soldDelta,
                        boughtDelta = boughtDelta,
                    ),
                )
            }

            registry.pendingTickByTick[soldTickReqId] =
                PendingTickByTickRequest { tick ->
                    soldPrices.set(LegPrices(bid = tick.bidPrice, ask = tick.askPrice))
                    emitIfReady()
                    true
                }
            registry.pendingTickByTick[boughtTickReqId] =
                PendingTickByTickRequest { tick ->
                    boughtPrices.set(LegPrices(bid = tick.bidPrice, ask = tick.askPrice))
                    emitIfReady()
                    true
                }
            registry.pendingContinuousMarketData[soldGreeksReqId] = PendingContinuousMarketDataRequest()
            registry.pendingContinuousMarketData[boughtGreeksReqId] = PendingContinuousMarketDataRequest()

            client.reqTickByTickData(soldTickReqId, buildOptionContract(soldContract), "BidAsk", 0, true)
            client.reqTickByTickData(boughtTickReqId, buildOptionContract(boughtContract), "BidAsk", 0, true)
            client.reqMktData(soldGreeksReqId, buildOptionContract(soldContract), "100", false, false, null)
            client.reqMktData(boughtGreeksReqId, buildOptionContract(boughtContract), "100", false, false, null)

            logger.debug {
                "Started spread credit stream for " +
                    "${soldContract.strike}P/${boughtContract.strike}P"
            }

            awaitClose {
                registry.pendingTickByTick.remove(soldTickReqId)
                client.cancelTickByTickData(soldTickReqId)
                registry.pendingTickByTick.remove(boughtTickReqId)
                client.cancelTickByTickData(boughtTickReqId)
                registry.pendingContinuousMarketData.remove(soldGreeksReqId)
                client.cancelMktData(soldGreeksReqId)
                registry.pendingContinuousMarketData.remove(boughtGreeksReqId)
                client.cancelMktData(boughtGreeksReqId)
                logger.debug {
                    "Cancelled spread credit stream for " +
                        "${soldContract.strike}P/${boughtContract.strike}P"
                }
            }
        }
}
