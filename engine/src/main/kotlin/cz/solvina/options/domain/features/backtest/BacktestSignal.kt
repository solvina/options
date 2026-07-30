package cz.solvina.options.domain.features.backtest

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal

sealed interface BacktestSignal {
    /**
     * How [OpenBracket.entryPrice] is reached — a property of the strategy's *intent*, not a run
     * knob, so a breakout and a mean-reversion entry can coexist in one backtest and each fills the
     * way its live order would.
     */
    enum class EntryMode {
        /**
         * Buy stop: fill when price trades **up** to the level. The breakout shape — the entry sits
         * above the market and only a move in the trade's favour triggers it.
         */
        STOP,

        /**
         * Buy limit: fill when price is **at or below** the level. The mean-reversion shape — the
         * entry sits at or above the market, so a gap down fills at the (better) open while a gap
         * up beyond the limit never fills at all.
         */
        LIMIT,
    }

    data class OpenBracket(
        val tradeId: String,
        val symbol: Symbol,
        val shares: Int,
        val entryPrice: BigDecimal,
        val stopLossPrice: BigDecimal,
        val profitTargetPrice: BigDecimal,
        /** Defaults to [EntryMode.STOP] so every pre-existing breakout signal fills unchanged. */
        val entryMode: EntryMode = EntryMode.STOP,
    ) : BacktestSignal
}
