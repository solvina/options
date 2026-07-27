package cz.solvina.options.domain.features.bars

import kotlin.math.max

/**
 * Wilder's RSI — the one implementation in the codebase.
 *
 * Wilder smoothing is recursive from the first bar of the series, so an RSI is **not** reproducible
 * from a bounded trailing window: feeding the same closes in the same order is the only way two
 * callers agree. That is why this is a rolling accumulator rather than a pure function over a
 * window, with [RsiCalculator.last] as the batch convenience on top of it.
 */
class WilderRsi(
    private val period: Int,
) {
    private var prevClose: Double? = null
    private var avgGain = 0.0
    private var avgLoss = 0.0
    private var seededCount = 0
    private var seeded = false

    /** RSI after the last [update], or null until [period] + 1 closes have been seen. */
    var value: Double? = null
        private set

    /** The value before the last [update] — the slope, without callers keeping their own copy. */
    var previous: Double? = null
        private set

    fun update(close: Double) {
        val prev = prevClose
        prevClose = close
        if (prev == null) return
        val delta = close - prev
        val gain = max(delta, 0.0)
        val loss = max(-delta, 0.0)
        if (!seeded) {
            avgGain += gain
            avgLoss += loss
            seededCount++
            if (seededCount < period) return
            avgGain /= period
            avgLoss /= period
            seeded = true
        } else {
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
        }
        previous = value
        // No losses at all is 100 — except a dead-flat series, which is neutral rather than
        // maximally overbought (0/0 is not a strong up-move).
        value =
            when {
                avgLoss != 0.0 -> 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
                avgGain == 0.0 -> 50.0
                else -> 100.0
            }
    }
}

object RsiCalculator {
    /** RSI at the end of an oldest-first close series, or null with fewer than [period] + 1 closes. */
    fun last(
        closes: List<Double>,
        period: Int,
    ): Double? {
        if (period < 1) return null
        val rsi = WilderRsi(period)
        closes.forEach { rsi.update(it) }
        return rsi.value
    }
}
