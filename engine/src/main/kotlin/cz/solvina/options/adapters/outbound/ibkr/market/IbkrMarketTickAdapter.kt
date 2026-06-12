package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Contract
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrOptionParamsCache
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionContractKey
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
    private val contractFactory: IbkrContractFactory,
    private val contractCache: IbkrContractCache,
    private val optionParamsCache: IbkrOptionParamsCache,
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
    ): Flow<SpreadCreditTick> = perLegCreditFlow(soldContract, boughtContract)

    /** Subscribe to individual leg bid/ask via tick-by-tick + continuous Greeks. Works on live and paper. */
    private fun perLegCreditFlow(
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
            // onUpdate doubles as fallback for bid/ask when reqTickByTickData is not supported
            // (e.g. paper account gets error 10189 + then delayed data via reqMarketDataType(3))
            registry.pendingContinuousMarketData[soldGreeksReqId] =
                PendingContinuousMarketDataRequest(
                    onUpdate = { snap ->
                        val bid = snap.bid.takeIf { !it.isNaN() && it > 0 } ?: return@PendingContinuousMarketDataRequest
                        val ask = snap.ask.takeIf { !it.isNaN() && it > 0 } ?: return@PendingContinuousMarketDataRequest
                        soldPrices.set(LegPrices(bid = bid, ask = ask))
                        emitIfReady()
                    },
                )
            registry.pendingContinuousMarketData[boughtGreeksReqId] =
                PendingContinuousMarketDataRequest(
                    onUpdate = { snap ->
                        val bid = snap.bid.takeIf { !it.isNaN() && it > 0 } ?: return@PendingContinuousMarketDataRequest
                        val ask = snap.ask.takeIf { !it.isNaN() && it > 0 } ?: return@PendingContinuousMarketDataRequest
                        boughtPrices.set(LegPrices(bid = bid, ask = ask))
                        emitIfReady()
                    },
                )

            val soldContract4Mkt = contractForMktData(soldContract)
            val boughtContract4Mkt = contractForMktData(boughtContract)

            client.reqTickByTickData(soldTickReqId, soldContract4Mkt, "BidAsk", 0, true)
            client.reqTickByTickData(boughtTickReqId, boughtContract4Mkt, "BidAsk", 0, true)
            client.reqMktData(soldGreeksReqId, soldContract4Mkt, "100", false, false, null)
            client.reqMktData(boughtGreeksReqId, boughtContract4Mkt, "100", false, false, null)

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

    // Resolves the contract for market data requests using a conId where possible to avoid
    // the "ambiguous" error 200 for multi-exchange symbols (e.g. ASML on EUREX/AEB).
    // We ONLY use cached conIds (no fetch) to avoid blocking market data setup for 5+ seconds.
    // If the conId is not cached, we fall back to the enriched spec with exchange/tradingClass
    // from optionParamsCache, which is the same approach that works during the option chain phase.
    private fun contractForMktData(contract: OptionContract): Contract {
        val key = OptionContractKey(contract.symbol, contract.expiry, contract.strike, contract.type)
        val conId = contractCache.getCachedOptionConId(key)
        if (conId != null) return contractFactory.conIdContract(conId)
        val params = optionParamsCache.getCached(contract.symbol)
        return contractFactory.optionContract(
            contract,
            exchangeOverride = params?.exchange,
            tradingClass = params?.tradingClass,
            multiplierOverride = params?.multiplier,
        )
    }
}
