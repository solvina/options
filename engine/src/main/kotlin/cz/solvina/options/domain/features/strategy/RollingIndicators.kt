package cz.solvina.options.domain.features.strategy

import cz.solvina.options.domain.features.bars.WilderRsi

/**
 * Per-symbol rolling indicator state: SMA (any period) + Wilder RSI + the previous RSI for slope,
 * plus an SMA of the RSI itself for strategies that trade RSI against its own moving average.
 *
 * Updated incrementally, one close at a time. Wilder's RSI is recursively smoothed from the first
 * bar it ever saw, so it is *not* reproducible from a bounded trailing window — which is exactly
 * why strategies carry this state rather than recomputing per decision.
 */
class RollingIndicators(
    rsiPeriod: Int,
) {
    private val closes = ArrayDeque<Double>()
    private val wilder = WilderRsi(rsiPeriod)
    private val rsiValues = ArrayDeque<Double>()

    val rsi: Double? get() = wilder.value

    val prevRsi: Double? get() = wilder.previous

    fun update(close: Double) {
        closes.addLast(close)
        if (closes.size > WINDOW) closes.removeFirst()
        wilder.update(close)
        wilder.value?.let {
            rsiValues.addLast(it)
            if (rsiValues.size > WINDOW) rsiValues.removeFirst()
        }
    }

    fun sma(period: Int): Double? = mean(closes, period)

    /**
     * SMA of the RSI series. Its first value only appears [period] bars after RSI itself starts, so
     * a strategy using it must declare the sum of both as warm-up.
     */
    fun rsiSma(period: Int): Double? = mean(rsiValues, period)

    /** SMA of the RSI as of the previous bar — the other half of a cross test. */
    fun prevRsiSma(period: Int): Double? = mean(rsiValues, period, skipLast = 1)

    private fun mean(
        values: ArrayDeque<Double>,
        period: Int,
        skipLast: Int = 0,
    ): Double? {
        val end = values.size - skipLast
        if (period <= 0 || end < period) return null
        // Indexed sum instead of toList().takeLast(): called more than once per bar, the list
        // copies are pure garbage on long backtests.
        var sum = 0.0
        for (i in end - period until end) sum += values[i]
        return sum / period
    }

    private companion object {
        /** Longest lookback any strategy may ask for; bounds the memory a long backtest holds. */
        const val WINDOW = 400
    }
}
