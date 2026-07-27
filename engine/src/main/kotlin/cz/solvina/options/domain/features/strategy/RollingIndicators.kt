package cz.solvina.options.domain.features.strategy

import kotlin.math.max

/**
 * Per-symbol rolling indicator state: SMA (any period) + Wilder RSI + the previous RSI for slope.
 *
 * Updated incrementally, one close at a time. Wilder's RSI is recursively smoothed from the first
 * bar it ever saw, so it is *not* reproducible from a bounded trailing window — which is exactly
 * why strategies carry this state rather than recomputing per decision.
 */
class RollingIndicators(
    private val rsiPeriod: Int,
) {
    private val closes = ArrayDeque<Double>()
    private var prevClose: Double? = null
    private var avgGain = 0.0
    private var avgLoss = 0.0
    private var seededCount = 0
    private var seeded = false

    var rsi: Double? = null
        private set

    var prevRsi: Double? = null
        private set

    fun update(close: Double) {
        closes.addLast(close)
        if (closes.size > 400) closes.removeFirst()
        val prev = prevClose
        if (prev != null) {
            val delta = close - prev
            val gain = max(delta, 0.0)
            val loss = max(-delta, 0.0)
            if (!seeded) {
                avgGain += gain
                avgLoss += loss
                seededCount++
                if (seededCount == rsiPeriod) {
                    avgGain /= rsiPeriod
                    avgLoss /= rsiPeriod
                    seeded = true
                }
            } else {
                avgGain = (avgGain * (rsiPeriod - 1) + gain) / rsiPeriod
                avgLoss = (avgLoss * (rsiPeriod - 1) + loss) / rsiPeriod
            }
            if (seeded) {
                prevRsi = rsi
                rsi = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
            }
        }
        prevClose = close
    }

    fun sma(period: Int): Double? {
        if (period <= 0 || closes.size < period) return null
        // Indexed sum instead of toList().takeLast(): called twice per bar, the list copies
        // are pure garbage on long backtests.
        var sum = 0.0
        for (i in closes.size - period until closes.size) sum += closes[i]
        return sum / period
    }
}
