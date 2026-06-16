package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Contract
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.IbkrRateLimiter
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
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
    private val rateLimiter: IbkrRateLimiter,
    // Independent scope for conId resolution so a bounded caller-side wait never cancels (and thus
    // poisons) an in-flight IBKR contract lookup — a late-but-successful response still caches.
    // Injected (the shared executionCoroutineScope bean) so it is decoupled from any caller's
    // coroutine and overridable in tests.
    private val conIdScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MarketTickPort {
    private val conIdResolveTimeoutMs = 1_500L

    override fun streamUnderlyingPrice(symbol: Symbol): Flow<Double> =
        callbackFlow {
            val reqId = registry.nextReqId()
            rateLimiter.acquireMarketDataLine()
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
                rateLimiter.releaseMarketDataLine()
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
            val streamStartNanos = System.nanoTime()
            val firstTickLogged = AtomicBoolean(false)
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
                if (firstTickLogged.compareAndSet(false, true)) {
                    logger.info {
                        "[${soldContract.symbol}] First spread-credit tick in " +
                            "${(System.nanoTime() - streamStartNanos) / 1_000_000}ms (${soldContract.strike}P/${boughtContract.strike}P)"
                    }
                }
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

            // Resolve leg conIds SEQUENTIALLY (not concurrently): IBKR paces near-simultaneous
            // reqContractDetails for the same underlying/expiry, delaying the second response by ~5s
            // (past the lookup's timeout). One request at a time resolves in ~400ms and caches.
            val soldContract4Mkt = contractForMktData(soldContract)
            val boughtContract4Mkt = contractForMktData(boughtContract)

            // 4 market-data lines: 2 tick-by-tick (bid/ask) + 2 continuous (greeks/fallback bid/ask).
            // DIAGNOSTIC: log free lines before acquiring — if ~0, lines are exhausted and the acquire
            // below blocks, delaying the subscription past calculateFreshCredit's 3s tick wait.
            logger.info {
                "[${soldContract.symbol}] Spread credit stream acquiring 4 mkt-data lines " +
                    "(available=${rateLimiter.availableMarketDataLines()}) for ${soldContract.strike}P/${boughtContract.strike}P"
            }
            repeat(4) { rateLimiter.acquireMarketDataLine() }
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
                repeat(4) { rateLimiter.releaseMarketDataLine() }
                logger.debug {
                    "Cancelled spread credit stream for " +
                        "${soldContract.strike}P/${boughtContract.strike}P"
                }
            }
        }

    // Resolves the contract for market data requests using a conId where possible to avoid
    // the "ambiguous" error 200 for multi-exchange symbols (e.g. ASML on EUREX/AEB).
    // Strategy: Try cached conId first (instant), then fast fetch (100ms timeout), then fallback.
    // NOTE: Adding exchange/tradingClass causes error 200 for US options, so we use minimal spec.
    private suspend fun contractForMktData(contract: OptionContract): Contract {
        val key = OptionContractKey(contract.symbol, contract.expiry, contract.strike, contract.type)

        // Try cache first (instant)
        contractCache.getCachedOptionConId(key)?.let {
            logger.debug { "[${contract.symbol}] mkt-data conId from cache (${contract.strike}P ${contract.expiry})" }
            return contractFactory.conIdContract(it)
        }

        // Resolve on an independent scope so the bounded wait below only stops *waiting* — it never
        // cancels the IBKR lookup. The previous withTimeout(100) cancelled the fetch itself, which
        // removed the pending request, discarded IBKR's ~250ms-late response, and left the conId
        // permanently unresolved → ambiguous minimal-spec market data → no tick → no fill. With the
        // fetch detached, a late-but-successful response still caches the conId for the next request.
        val fetch = conIdScope.async { contractCache.getOrFetchOptionConId(key) }
        val conId =
            try {
                // withTimeoutOrNull → null on OUR timeout; an outer-flow cancellation still propagates.
                withTimeoutOrNull(conIdResolveTimeoutMs) { fetch.await() }
            } catch (e: CancellationException) {
                throw e // outer flow cancelled — propagate; the detached fetch still caches the conId
            } catch (_: Exception) {
                null // lookup genuinely failed (ambiguous/missing strike) — fall back to minimal spec
            }
        conId?.let {
            logger.debug { "[${contract.symbol}] mkt-data conId resolved=$it (${contract.strike}P ${contract.expiry})" }
            return contractFactory.conIdContract(it)
        }

        // Fall back to minimal contract spec (symbol+secType+currency+expiry+right only).
        // NOT adding exchange/tradingClass because it causes error 200 "not found" for US options.
        // DIAGNOSTIC: the minimal spec is ambiguous for multi-exchange symbols → IBKR error 200 →
        // no tick → calculateFreshCredit aborts. WARN so the fallback rate is visible in the journal.
        logger.warn {
            "[${contract.symbol}] mkt-data conId UNRESOLVED for ${contract.strike}P ${contract.expiry} ${contract.type} — " +
                "using ambiguous minimal-spec contract (expect IBKR error 200 / no tick → no fill)"
        }
        return contractFactory.optionContract(contract)
    }
}
