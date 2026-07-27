package cz.solvina.options.domain.features.strategy

import cz.solvina.options.domain.features.bars.Candle
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal

/**
 * Everything [StockStrategy.decide] is allowed to see for one bar of one symbol.
 *
 * Deliberately a snapshot of *data*, never a handle on the host: no account object to mutate, no
 * order port, no clock. Whatever a strategy needs must arrive here, which keeps the backtest and
 * live hosts substitutable by construction.
 */
data class StrategyContext(
    val symbol: Symbol,
    /** The candle being decided, on [StrategyInputs.primary]. */
    val candle: Candle,
    /**
     * Latest closed candle per declared timeframe (including the primary). A multi-timeframe
     * strategy — daily trend filter with a 4-hour entry, say — reads its slower series through
     * [latest] instead of holding its own aggregation.
     */
    private val byTimeframe: Map<Timeframe, Candle> = emptyMap(),
    /** Account equity at this bar. Backs %-of-equity sizing and the ruin guard. */
    val equity: BigDecimal,
    val openPositions: Int,
    val pendingPositions: Int,
) {
    /** Latest closed candle on [timeframe], or null when that series has not produced one yet. */
    fun latest(timeframe: Timeframe): Candle? = byTimeframe[timeframe]

    val exposureCount: Int get() = openPositions + pendingPositions
}
