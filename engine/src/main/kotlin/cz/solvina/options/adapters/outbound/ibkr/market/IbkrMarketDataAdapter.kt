package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionContractKey
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderIdCounter
import cz.solvina.options.domain.features.market.MarketDataHealthTracker
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.market.model.ComboQuote
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.lastOrNull
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

// Held position-quote-stream tunables (2026-07-21): a leg stream that hasn't ticked within this
// window is treated as stale (caller falls back to a snapshot); the line acquire is bounded so a
// drained pool skips opening the stream rather than blocking.
private const val POSITION_STREAM_STALENESS_MS = 15_000L
private const val POSITION_STREAM_LINE_TIMEOUT_MS = 5_000L

@Component
class IbkrMarketDataAdapter(
    private val registry: IbkrMarketDataRegistry,
    private val marketSnapshotHelper: MarketSnapshotHelper,
    private val idCounter: IbkrOrderIdCounter,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
    private val historicalDataAdapter: IbkrHistoricalDataAdapter,
    private val healthTracker: MarketDataHealthTracker,
    private val contractCache: IbkrContractCache,
) : MarketDataPort {
    // Every underlying-price fetch feeds the market-data flow signal (see MarketDataHealthTracker):
    // success ⇒ data is live, the "No price data" failure ⇒ starved even if the socket is connected.
    override suspend fun getUnderlyingPrice(symbol: Symbol): Money =
        try {
            resolveUnderlyingPrice(symbol).also { healthTracker.recordSuccess() }
        } catch (e: Throwable) {
            healthTracker.recordFailure("[$symbol] ${e.message}")
            throw e
        }

    private suspend fun resolveUnderlyingPrice(symbol: Symbol): Money {
        val snapshot =
            marketSnapshotHelper.reqMktDataSnapshot(
                symbol,
                contractFactory.stockContract(symbol),
                "underlying price (scanner/exit/strategy pricing)",
                SnapshotReady.STOCK_PRICE,
            )
        val price = snapshot.last.takeIf { it > 0 } ?: snapshot.close.takeIf { it > 0 }
        if (price != null) return Money(BigDecimal(price).setScale(2, RoundingMode.HALF_UP))

        // No live/delayed snapshot — fall back to last historical close (e.g. EU symbols on paper)
        logger.debug { "[$symbol] Live price unavailable, falling back to last historical close" }
        val lastBar = runCatching { historicalDataAdapter.fetchDailyPriceBars(symbol, 5).lastOrNull() }.getOrNull()
        val histClose = lastBar?.close ?: error("No price data available for $symbol")
        logger.info { "[$symbol] Using historical close price: $histClose" }
        return Money(histClose.setScale(2, RoundingMode.HALF_UP))
    }

    override suspend fun getOptionMidLive(contract: OptionContract): Money? {
        val snapshot =
            marketSnapshotHelper.reqMktDataSnapshot(
                contract.symbol,
                contractFactory.optionContract(contract),
                "option live mid (exit/close decision, no BS fallback)",
                SnapshotReady.OPTION_PRICE,
            )
        val mid = midPrice(snapshot.bid, snapshot.ask)
        // Live bid/ask only — deliberately no Black-Scholes / previous-day fallback. Price-based
        // exit decisions must not run on synthetic data; a null tells the caller to skip the cycle.
        return if (mid > BigDecimal.ZERO) Money(mid) else null
    }

    override suspend fun getOptionMid(contract: OptionContract): Money {
        val snapshot =
            marketSnapshotHelper.reqMktDataSnapshot(
                contract.symbol,
                contractFactory.optionContract(contract),
                "option mid (reporting, BS/historical fallback allowed)",
                SnapshotReady.OPTION_PRICE,
            )
        val mid = midPrice(snapshot.bid, snapshot.ask)
        if (mid > BigDecimal.ZERO) return Money(mid)

        // No live market — do NOT fabricate a price. Log for investigation and return zero so callers
        // (reporting/monitoring) render "unavailable" rather than acting on calculated data.
        logger.warn {
            "[${contract.symbol}] getOptionMid: no live bid/ask for ${contract.strike}${contract.type} " +
                "exp=${contract.expiry} (bid=${snapshot.bid} ask=${snapshot.ask}) — returning 0, no BS fallback"
        }
        return Money(BigDecimal.ZERO)
    }

    // Quotes the spread as one BAG instrument instead of inferring it from the two leg books. The
    // conIds are already warm here: strike selection fetched greeks for both legs moments earlier
    // via the same cache, so this adds a market-data line but no contract-details round trip.
    //
    // Every failure mode returns null and the caller falls back to the leg-derived natural cross, so
    // the worst case is exactly the behaviour that preceded this method.
    override suspend fun getComboQuote(
        sold: OptionContract,
        bought: OptionContract,
    ): ComboQuote? {
        val soldConId = contractCache.getCachedOptionConId(OptionContractKey(sold.symbol, sold.expiry, sold.strike, sold.type))
        val boughtConId =
            contractCache.getCachedOptionConId(OptionContractKey(bought.symbol, bought.expiry, bought.strike, bought.type))
        if (soldConId == null || boughtConId == null) {
            logger.debug { "[${sold.symbol}] No cached conIds for combo quote (sold=$soldConId bought=$boughtConId) — using leg cross" }
            return null
        }
        val snapshot =
            runCatching {
                // TWS_LIMITS: +1 market-data line for one snapshot, self-retiring via the helper's
                // finally. Fires only for a pair that already passed strike selection.
                marketSnapshotHelper.reqMktDataSnapshot(
                    sold.symbol,
                    contractFactory.bagContract(sold, soldConId, boughtConId),
                    "combo BAG quote (scanner achievable-credit check)",
                    SnapshotReady.COMBO_QUOTE,
                )
            }.getOrElse { e ->
                logger.debug(e) { "[${sold.symbol}] Combo quote request failed — using leg cross: ${e.message}" }
                return null
            }
        if (snapshot.bid.isNaN() || snapshot.ask.isNaN()) return null
        return ComboQuote(
            bid = BigDecimal(snapshot.bid).setScale(4, RoundingMode.HALF_UP),
            ask = BigDecimal(snapshot.ask).setScale(4, RoundingMode.HALF_UP),
        )
    }

    // ---- Persistent per-leg option quote streams for open positions (2026-07-21) ----
    // The exit monitor used to snapshot (subscribe→wait→cancel) every leg every 60s, churning the
    // options data farm ~2400×/hour. Held streams (like the stable stock-farm pattern) cost 2 lines
    // per spread — sized to the EXIT reserve (maxOpenSpreads × 2). streamedOptionMid reads the
    // registry's continuously-updated snapshot; the monitor falls back to a one-off snapshot when a
    // stream is missing/stale, so this can only reduce churn, never regress the exit logic.
    private val positionStreams = ConcurrentHashMap<OptionContractKey, Int>()

    private fun keyOf(c: OptionContract) = OptionContractKey(c.symbol, c.expiry, c.strike, c.type)

    override suspend fun reconcilePositionQuoteStreams(contracts: List<OptionContract>) {
        val wanted = contracts.map(::keyOf).toSet()
        // Cancel streams whose position has closed (no longer in the wanted set).
        positionStreams.keys.filter { it !in wanted }.forEach { key ->
            positionStreams.remove(key)?.let { reqId ->
                registry.remove(reqId)
                runCatching { client.cancelMktData(reqId) }
                    .onSuccess { MarketDataLineTracker.unsubscribed("[${key.symbol}] position leg quote (reqMktData)") }
            }
        }
        // Open a held stream for each newly-tracked leg.
        for (contract in contracts) {
            val key = keyOf(contract)
            if (positionStreams.containsKey(key)) continue
            val reqId = idCounter.nextOrderId()
            registry.addPendingContinuousMarketDataRequest(
                reqId,
                contract.symbol,
                {}, // we only care about the latest snapshot
            )
            positionStreams[key] = reqId
            // TWS_LIMITS: +1 market-data line per open-position leg = 2 per spread (EXIT reserve, sized
            // maxOpenSpreads × 2). HELD FOR THE LIFE OF THE POSITION — retires only when the leg drops
            // out of the wanted set (position closed) → cancelMktData in the reconcile loop above, NOT
            // per pricing cycle. Subscribe-on-open / unsubscribe-on-close; steady occupancy, low churn.
            runCatching { client.reqMktData(reqId, contractFactory.optionContract(contract), "", false, false, null) }
                .onSuccess { MarketDataLineTracker.subscribed("[${contract.symbol}] position leg quote (reqMktData)") }
                .onFailure { e ->
                    positionStreams.remove(key)
                    registry.remove(reqId)
                    logger.warn { "[${contract.symbol}] position quote stream reqMktData failed: ${e.message}" }
                }
        }
    }

    override fun streamedOptionMid(contract: OptionContract): Money? {
        val reqId = positionStreams[keyOf(contract)] ?: return null
        val snap = registry.getPendingContinuousMarketDataSnapshot(reqId) ?: return null
        if (Duration.between(snap.asOf, Instant.now()).toMillis() > POSITION_STREAM_STALENESS_MS) return null
        val mid = midPrice(snap.bid, snap.ask)
        return if (mid > BigDecimal.ZERO) Money(mid) else null
    }

    // Spot for free off the leg streams: IBKR's option computation tick carries the underlying it
    // priced the greeks against, so a symbol with held legs already has spot on the wire. Any leg of
    // the symbol will do — they all reference the same underlying — so take the freshest reading.
    override fun streamedUnderlyingPrice(symbol: Symbol): Money? {
        val now = Instant.now()
        val freshest =
            positionStreams
                .entries
                .asSequence()
                .filter { it.key.symbol == symbol }
                .mapNotNull { registry.getPendingContinuousMarketDataSnapshot(it.value) }
                .filter { it.underlyingPrice > 0 && it.underlyingPriceAsOf != null }
                .filter { Duration.between(it.underlyingPriceAsOf, now).toMillis() <= POSITION_STREAM_STALENESS_MS }
                .maxByOrNull { it.underlyingPriceAsOf!! }
                ?: return null
        return Money(BigDecimal(freshest.underlyingPrice).setScale(2, RoundingMode.HALF_UP))
    }
}
