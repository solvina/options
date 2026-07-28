package cz.solvina.options.domain.features.strategy

import cz.solvina.options.domain.features.bars.Timeframe
import kotlin.math.ceil

/**
 * How far back to read bars so a strategy's indicators are warm.
 *
 * Shared by both hosts on purpose. The backtest and the live runner must warm on the same span, or
 * the live signal is computed from a different series than the one that was tested — which is
 * exactly the drift the host-neutral seam exists to prevent.
 */
object StrategyWarmup {
    private const val MIN_WARMUP_CALENDAR_DAYS = 30L

    /**
     * Warm-up bars → calendar days, via trading days (~5 per 7 calendar) with a 2× safety factor
     * for holidays and thin history. Counting in bars keeps intraday timeframes honest: 200
     * five-minute bars is three sessions, not 200 days.
     */
    fun calendarDays(
        warmupBars: Int,
        timeframe: Timeframe,
    ): Long {
        val barsPerTradingDay =
            when (timeframe) {
                Timeframe.DAILY -> 1.0
                Timeframe.FOUR_HOUR -> 2.0 // RTH session ≈ 6.5h → 2 four-hour bars
                Timeframe.FIVE_MIN -> 78.0 // 6.5h RTH / 5 min
            }
        val tradingDays = ceil(warmupBars / barsPerTradingDay).toLong()
        return (tradingDays * 2L).coerceAtLeast(MIN_WARMUP_CALENDAR_DAYS)
    }
}
