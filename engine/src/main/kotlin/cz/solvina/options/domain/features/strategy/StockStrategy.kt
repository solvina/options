package cz.solvina.options.domain.features.strategy

import cz.solvina.options.domain.features.bars.Candle
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol

/**
 * One strategy in the stock strategy library — the unit that gets listed, tuned, assigned to
 * symbols and backtested.
 *
 * Deliberately **host-neutral**: no `BacktestAccountView`, no `BacktestSignal`, no broker types.
 * The same instance is driven by the backtest replay host ([cz.solvina.options.domain.features.backtest.StrategyBacktestAdapter])
 * and, later, by the live host — so a rule can never exist in one and not the other. That
 * divergence is what makes a backtest lie; the flag strategy's live-only 5-second `LIVE_BAR`
 * trigger is the cautionary example already in this codebase.
 *
 * ### On statefulness
 * A strategy keeps rolling indicator state across [decide] calls. This is *not* a purity leak: the
 * state is a deterministic function of the candle sequence it has been fed, so both hosts produce
 * identical decisions as long as they feed identical bars. It cannot be avoided anyway — Wilder's
 * RSI is recursively smoothed from the start of the series, so recomputing it from a bounded window
 * would silently give different numbers than an incremental update.
 *
 * What a strategy must **never** do inside [decide]: read a clock, touch the network or DB, or
 * mutate anything a host owns.
 */
interface StockStrategy {
    /** Stable identifier, persisted on runs, assignments and positions. Never rename in place. */
    val id: String

    val displayName: String

    /** Declares the tunable surface — drives validation, the sweep grid and the generic UI form. */
    val params: List<ParamDescriptor>

    /** Declares what data the strategy consumes, so a host can refuse what it cannot honestly feed. */
    val inputs: StrategyInputs

    /** Returns why [params] is unusable, or null when it is. */
    fun validate(params: StrategyParams): String?

    /**
     * Returns a fresh instance configured with [params] on [timeframe].
     *
     * The registered bean is a **template**: it answers [id], [displayName], [params], [validate]
     * and nothing else. Only instances handed out here carry rolling state and may [decide] — a
     * strategy is stateful per run, so a shared singleton would mix two runs' indicator state.
     */
    fun withParams(
        params: StrategyParams,
        timeframe: Timeframe,
    ): StockStrategy

    /**
     * Seeds rolling state from pre-period bars. Must not produce decisions — warm-up bars are
     * history, not tradeable.
     */
    fun warmup(
        symbol: Symbol,
        history: Map<Timeframe, List<Candle>>,
    )

    /**
     * Advances state with the candle in [ctx] and returns an entry intent, or null.
     *
     * Called for **every** bar including ones where the strategy cannot trade (position caps,
     * insufficient warm-up). Implementations must therefore update indicator state *before* any
     * early return, or state silently diverges between a capped and an uncapped run.
     */
    fun decide(ctx: StrategyContext): Decision?
}

/**
 * What a strategy needs fed to it.
 *
 * [timeframes] is ordered; the first is the primary (decision) timeframe. [warmupBars] is counted
 * in primary-timeframe bars and is the strategy's own business — a host must not guess it from a
 * parameter name.
 */
data class StrategyInputs(
    val timeframes: List<Timeframe>,
    val warmupBars: Int,
    /**
     * True when the strategy needs raw ticks. There is no tick history in the bar store, so the
     * backtest host **rejects** such a strategy rather than approximating it with candles: a
     * tick-dependent strategy showing clean backtest numbers is worse than no backtest at all.
     */
    val requiresTicks: Boolean = false,
) {
    val primary: Timeframe get() = timeframes.first()
}
