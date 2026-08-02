package cz.solvina.options.domain.features.market

import cz.solvina.options.domain.features.market.model.ComboQuote
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal

interface MarketDataPort {
    suspend fun getUnderlyingPrice(symbol: Symbol): Money

    suspend fun getOptionMid(contract: OptionContract): Money

    /**
     * Option mid built only from a live bid/ask. Returns null when no fresh quote is available.
     *
     * Callers making price-based exit decisions (take-profit / stop-loss) must use this and skip
     * the check when it is null — they must NOT act on the synthetic Black-Scholes or previous-day
     * fallback that [getOptionMid] may return. The default delegates to [getOptionMid] for adapters
     * (tests/backtest) whose prices are already deterministic; the production IBKR adapter overrides
     * this to suppress its Black-Scholes fallback.
     */
    suspend fun getOptionMidLive(contract: OptionContract): Money? = getOptionMid(contract).takeIf { it.amount > BigDecimal.ZERO }

    /**
     * Persistent per-leg quote streams for open positions — replaces the exit monitor's per-cycle
     * snapshot churn on the options data farm with held streams (the stock farm's stable pattern).
     * [reconcilePositionQuoteStreams] opens a lightweight streaming subscription (1 line per leg,
     * bid/ask only) for each contract not yet streamed and cancels streams no longer wanted;
     * [streamedOptionMid] returns the latest cached mid when a fresh (non-stale) tick exists, else
     * null so the caller falls back to [getOptionMidLive]. Sized to the EXIT reserve
     * (maxOpenSpreads × 2 legs). Default no-op/null leaves test/backtest adapters unchanged; the
     * IBKR adapter overrides.
     */
    suspend fun reconcilePositionQuoteStreams(contracts: List<OptionContract>) {}

    fun streamedOptionMid(contract: OptionContract): Money? = null

    /**
     * Spot for [symbol] read off an already-open position leg stream, or null when no fresh reading
     * exists. IBKR ships the underlying price it priced the greeks against on every option
     * computation tick, so a symbol with held leg streams already has spot on the wire — reading it
     * here costs no extra market-data line and replaces a per-cycle subscribe→read→cancel snapshot.
     *
     * This is IBKR's model input, not a stock-farm last trade: it tracks spot to within a few cents
     * and only refreshes when a computation tick fires. Fine for display/telemetry
     * ([cz.solvina.options.domain.features.spread.SpreadCloser.recordLastValue]); callers whose exit
     * decision turns on spot should keep using [getUnderlyingPrice]. Default null leaves
     * test/backtest adapters on the snapshot path; the IBKR adapter overrides.
     */
    fun streamedUnderlyingPrice(symbol: Symbol): Money? = null

    /**
     * Bid/ask for the [sold]+[bought] pair quoted as one BAG instrument, or null when unavailable.
     *
     * Costs one extra market-data line for the duration of a single snapshot, and only fires for a
     * pair that already cleared strike selection — a handful of requests per scan, not per symbol.
     * It does NOT replace the per-leg quotes: those are still required for delta-band selection, the
     * mid credit, the per-leg liquidity gate and the fresh-tick recheck at execution.
     *
     * Default null keeps test/backtest adapters on the leg-derived natural cross; the IBKR adapter
     * overrides. Null is a first-class answer here — every caller must fall back rather than skip.
     */
    suspend fun getComboQuote(
        sold: OptionContract,
        bought: OptionContract,
    ): ComboQuote? = null
}
