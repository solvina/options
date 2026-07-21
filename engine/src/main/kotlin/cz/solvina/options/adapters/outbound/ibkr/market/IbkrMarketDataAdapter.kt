package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionController
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionContractKey
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingContinuousMarketDataRequest
import cz.solvina.options.domain.features.market.MarketDataHealthTracker
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.market.MarketDataPriority
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
    private val client: EClientSocket,
    private val admission: IbkrAdmissionController,
    private val contractFactory: IbkrContractFactory,
    private val historicalDataAdapter: IbkrHistoricalDataAdapter,
    private val healthTracker: MarketDataHealthTracker,
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
        val snapshot = reqMktDataSnapshot(registry, client, admission, contractFactory.stockContract(symbol), "", SnapshotReady.STOCK_PRICE)
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
            reqMktDataSnapshot(registry, client, admission, contractFactory.optionContract(contract), "", SnapshotReady.OPTION_PRICE)
        val mid = midPrice(snapshot.bid, snapshot.ask)
        // Live bid/ask only — deliberately no Black-Scholes / previous-day fallback. Price-based
        // exit decisions must not run on synthetic data; a null tells the caller to skip the cycle.
        return if (mid > BigDecimal.ZERO) Money(mid) else null
    }

    override suspend fun getOptionMid(contract: OptionContract): Money {
        val snapshot =
            reqMktDataSnapshot(registry, client, admission, contractFactory.optionContract(contract), "", SnapshotReady.OPTION_PRICE)
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
                registry.pendingContinuousMarketData.remove(reqId)
                runCatching { client.cancelMktData(reqId) }
                admission.releaseMarketDataLine(MarketDataPriority.EXIT)
            }
        }
        // Open a held stream for each newly-tracked leg.
        for (contract in contracts) {
            val key = keyOf(contract)
            if (positionStreams.containsKey(key)) continue
            if (!admission.tryAcquireMarketDataLine(MarketDataPriority.EXIT, POSITION_STREAM_LINE_TIMEOUT_MS)) {
                logger.warn { "[${contract.symbol}] no EXIT line for position quote stream — retry next cycle" }
                continue
            }
            val reqId = registry.nextReqId()
            registry.pendingContinuousMarketData[reqId] = PendingContinuousMarketDataRequest()
            positionStreams[key] = reqId
            runCatching { client.reqMktData(reqId, contractFactory.optionContract(contract), "", false, false, null) }
                .onFailure { e ->
                    positionStreams.remove(key)
                    registry.pendingContinuousMarketData.remove(reqId)
                    admission.releaseMarketDataLine(MarketDataPriority.EXIT)
                    logger.warn { "[${contract.symbol}] position quote stream reqMktData failed: ${e.message}" }
                }
        }
    }

    override fun streamedOptionMid(contract: OptionContract): Money? {
        val reqId = positionStreams[keyOf(contract)] ?: return null
        val snap = registry.pendingContinuousMarketData[reqId]?.snapshot ?: return null
        if (Duration.between(snap.asOf, Instant.now()).toMillis() > POSITION_STREAM_STALENESS_MS) return null
        val mid = midPrice(snap.bid, snap.ask)
        return if (mid > BigDecimal.ZERO) Money(mid) else null
    }
}
