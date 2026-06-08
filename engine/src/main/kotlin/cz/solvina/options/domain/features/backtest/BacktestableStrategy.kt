package cz.solvina.options.domain.features.backtest

import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.Instant

interface BacktestableStrategy {
    /**
     * Called once before simulation starts with pre-period warm-up bars only.
     * Implementations should seed internal state (buffers, detectors) but must not trade.
     */
    fun initialize(
        symbols: List<Symbol>,
        warmupBars: Map<Symbol, List<FiveMinuteBar>>,
    )

    /**
     * Called for every bar in the backtest period, in chronological order.
     * Returns zero or more entry signals; the engine handles fill simulation.
     */
    fun onBar(
        symbol: Symbol,
        bar: FiveMinuteBar,
        account: BacktestAccountView,
    ): List<BacktestSignal>

    /** Engine notifies the strategy that a pending entry was filled at [fillPrice]. */
    fun onEntryFilled(
        tradeId: String,
        fillPrice: BigDecimal,
        filledAt: Instant,
    )

    /** Engine notifies the strategy that a pending entry expired unfilled (EOD cancel). */
    fun onEntryExpired(tradeId: String)

    /**
     * Engine notifies the strategy that an open position was closed.
     * [highestSeen] and [lowestSeen] are the extreme prices observed during the hold.
     */
    fun onPositionClosed(
        tradeId: String,
        closePrice: BigDecimal,
        closeReason: String,
        closedAt: Instant,
        highestSeen: BigDecimal,
        lowestSeen: BigDecimal,
    )

    /** Returns all completed trades for inclusion in the backtest result. */
    fun trades(): List<*>
}
