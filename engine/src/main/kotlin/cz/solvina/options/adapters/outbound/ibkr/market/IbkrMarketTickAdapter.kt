package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Contract
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrRequestRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.MarketDataSnapshot
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingContinuousMarketDataRequest
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingTickByTickRequest
import cz.solvina.options.adapters.outbound.ibkr.registry.TickByTickBidAsk
import cz.solvina.options.domain.features.market.MarketTickPort
import cz.solvina.options.domain.features.market.SpreadCreditTick
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

@Component
class IbkrMarketTickAdapter(
    private val registry: IbkrRequestRegistry,
    private val client: EClientSocket,
) : MarketTickPort {
    override fun streamUnderlyingPrice(symbol: Symbol): Flow<Double> =
        callbackFlow {
            val reqId = registry.nextDataReqId()
            registry.pendingContinuousMarketData[reqId] =
                PendingContinuousMarketDataRequest(
                    snapshot = MarketDataSnapshot(),
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
            val soldTickReqId = registry.nextDataReqId()
            val boughtTickReqId = registry.nextDataReqId()
            // reqMktData(snapshot=false) for continuous Greeks on each leg
            val soldGreeksReqId = registry.nextDataReqId()
            val boughtGreeksReqId = registry.nextDataReqId()

            // Mutable state — all callbacks fire on the single IBKR reader thread
            var soldBid = Double.NaN
            var soldAsk = Double.NaN
            var boughtBid = Double.NaN
            var boughtAsk = Double.NaN

            fun emitIfReady(tick: TickByTickBidAsk? = null) {
                val sB = soldBid
                val sA = soldAsk
                val bB = boughtBid
                val bA = boughtAsk
                if (sB.isNaN() || sA.isNaN() || bB.isNaN() || bA.isNaN()) return
                val soldMid = (sB + sA) / 2
                val boughtMid = (bB + bA) / 2
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
                        soldBid = sB,
                        soldAsk = sA,
                        boughtBid = bB,
                        boughtAsk = bA,
                        netCredit = soldMid - boughtMid,
                        soldDelta = soldDelta,
                        boughtDelta = boughtDelta,
                    ),
                )
            }

            registry.pendingTickByTick[soldTickReqId] =
                PendingTickByTickRequest { tick ->
                    soldBid = tick.bidPrice
                    soldAsk = tick.askPrice
                    emitIfReady(tick)
                    true
                }
            registry.pendingTickByTick[boughtTickReqId] =
                PendingTickByTickRequest { tick ->
                    boughtBid = tick.bidPrice
                    boughtAsk = tick.askPrice
                    emitIfReady(tick)
                    true
                }
            registry.pendingContinuousMarketData[soldGreeksReqId] =
                PendingContinuousMarketDataRequest(snapshot = MarketDataSnapshot())
            registry.pendingContinuousMarketData[boughtGreeksReqId] =
                PendingContinuousMarketDataRequest(snapshot = MarketDataSnapshot())

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

    // -------------------------------------------------------------------------

    private fun buildStockContract(symbol: Symbol): Contract =
        Contract().apply {
            symbol(symbol.value)
            secType("STK")
            currency("USD")
            exchange("SMART")
        }

    private fun buildOptionContract(contract: OptionContract): Contract =
        Contract().apply {
            symbol(contract.symbol.value)
            secType("OPT")
            currency("USD")
            exchange("SMART")
            lastTradeDateOrContractMonth(
                contract.expiry.format(DateTimeFormatter.ofPattern("yyyyMMdd")),
            )
            strike(contract.strike.toDouble())
            right(contract.type.ibkrCode)
            multiplier("100")
        }
}
