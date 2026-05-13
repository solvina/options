package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingContinuousMarketDataRequest
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingTickByTickRequest
import cz.solvina.options.domain.features.market.MarketTickPort
import cz.solvina.options.domain.features.market.SpreadCreditTick
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

private const val PAPER_CREDIT_POLL_MS = 3_000L

private data class LegPrices(
    val bid: Double = Double.NaN,
    val ask: Double = Double.NaN,
)

@Component
class IbkrMarketTickAdapter(
    private val registry: IbkrMarketDataRegistry,
    private val client: EClientSocket,
    private val ibkrConfig: IbkrConnectionConfig,
    private val contractFactory: IbkrContractFactory,
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
            client.reqMktData(reqId, contractFactory.stockContract(symbol), "", false, false, null)
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
        if (ibkrConfig.paperAccount) {
            paperCreditFlow(soldContract, boughtContract)
        } else {
            liveCreditFlow(soldContract, boughtContract)
        }

    /** Live: real-time tick-by-tick bid/ask + continuous Greeks subscription. */
    private fun liveCreditFlow(
        soldContract: OptionContract,
        boughtContract: OptionContract,
    ): Flow<SpreadCreditTick> =
        callbackFlow {
            val soldTickReqId = registry.nextReqId()
            val boughtTickReqId = registry.nextReqId()
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

            client.reqTickByTickData(soldTickReqId, contractFactory.optionContract(soldContract), "BidAsk", 0, true)
            client.reqTickByTickData(boughtTickReqId, contractFactory.optionContract(boughtContract), "BidAsk", 0, true)
            client.reqMktData(soldGreeksReqId, contractFactory.optionContract(soldContract), "100", false, false, null)
            client.reqMktData(boughtGreeksReqId, contractFactory.optionContract(boughtContract), "100", false, false, null)

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

    /**
     * Paper: poll continuous reqMktData snapshots every 3s.
     * tick-by-tick (reqTickByTick) is not supported for options on paper accounts (error 10189).
     * reqMarketDataType(3) is set at connection time so delayed prices are returned.
     */
    private fun paperCreditFlow(
        soldContract: OptionContract,
        boughtContract: OptionContract,
    ): Flow<SpreadCreditTick> =
        callbackFlow {
            val soldReqId = registry.nextReqId()
            val boughtReqId = registry.nextReqId()

            registry.pendingContinuousMarketData[soldReqId] = PendingContinuousMarketDataRequest()
            registry.pendingContinuousMarketData[boughtReqId] = PendingContinuousMarketDataRequest()

            client.reqMktData(soldReqId, contractFactory.optionContract(soldContract), "", false, false, null)
            client.reqMktData(boughtReqId, contractFactory.optionContract(boughtContract), "", false, false, null)

            logger.debug {
                "Started spread credit polling stream (paper) for " +
                    "${soldContract.strike}P/${boughtContract.strike}P"
            }

            launch {
                while (isActive) {
                    delay(PAPER_CREDIT_POLL_MS)
                    val s = registry.pendingContinuousMarketData[soldReqId]?.snapshot ?: continue
                    val b = registry.pendingContinuousMarketData[boughtReqId]?.snapshot ?: continue
                    // Delayed options data sends last/close but not bid/ask — fall back to those
                    val sBid = s.bid.takeIf { !it.isNaN() } ?: s.last.takeIf { !it.isNaN() } ?: s.close.takeIf { !it.isNaN() } ?: continue
                    val sAsk = s.ask.takeIf { !it.isNaN() } ?: s.last.takeIf { !it.isNaN() } ?: s.close.takeIf { !it.isNaN() } ?: continue
                    val bBid = b.bid.takeIf { !it.isNaN() } ?: b.last.takeIf { !it.isNaN() } ?: b.close.takeIf { !it.isNaN() } ?: continue
                    val bAsk = b.ask.takeIf { !it.isNaN() } ?: b.last.takeIf { !it.isNaN() } ?: b.close.takeIf { !it.isNaN() } ?: continue
                    trySend(
                        SpreadCreditTick(
                            soldBid = sBid,
                            soldAsk = sAsk,
                            boughtBid = bBid,
                            boughtAsk = bAsk,
                            netCredit = (sBid + sAsk) / 2.0 - (bBid + bAsk) / 2.0,
                            soldDelta = s.delta.takeIf { !it.isNaN() },
                            boughtDelta = b.delta.takeIf { !it.isNaN() },
                        ),
                    )
                }
            }

            awaitClose {
                registry.pendingContinuousMarketData.remove(soldReqId)
                client.cancelMktData(soldReqId)
                registry.pendingContinuousMarketData.remove(boughtReqId)
                client.cancelMktData(boughtReqId)
                logger.debug {
                    "Cancelled spread credit polling stream (paper) for " +
                        "${soldContract.strike}P/${boughtContract.strike}P"
                }
            }
        }
}
