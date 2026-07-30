package cz.solvina.options.domain.features.bars

/**
 * Money Flow Index — RSI's volume-weighted cousin, the one implementation in the codebase.
 *
 * Unlike [WilderRsi] this is a **simple** sum over the last [period] bars, not Wilder smoothing:
 * that is the standard MFI definition, and it means the value *is* reproducible from a bounded
 * trailing window. It is still exposed as a rolling accumulator so a strategy advances it one bar
 * at a time exactly as it does RSI, and [MfiCalculator.last] is the batch convenience on top.
 *
 * Money flow needs a bar, not a close: typical price is (high + low + close) / 3 and each bar's
 * raw flow is that times volume, classified positive or negative by whether typical price rose or
 * fell. An unchanged typical price contributes to neither side, per the standard definition.
 */
class MoneyFlow(
    private val period: Int,
) {
    private val positive = ArrayDeque<Double>()
    private val negative = ArrayDeque<Double>()
    private var prevTypical: Double? = null

    /** MFI after the last [update], or null until [period] classified bars have accumulated. */
    var value: Double? = null
        private set

    fun update(
        high: Double,
        low: Double,
        close: Double,
        volume: Double,
    ) {
        val typical = (high + low + close) / 3.0
        val prev = prevTypical
        prevTypical = typical
        if (prev == null) return

        val raw = typical * volume
        positive.addLast(if (typical > prev) raw else 0.0)
        negative.addLast(if (typical < prev) raw else 0.0)
        if (positive.size > period) {
            positive.removeFirst()
            negative.removeFirst()
        }
        if (positive.size < period) return

        val pos = positive.sum()
        val neg = negative.sum()
        // A window with no flow on either side (a dead-flat or zero-volume stretch) is neutral, not
        // maximally oversold — mirroring how WilderRsi treats 0/0. Reporting 0 there would fire an
        // oversold entry on a symbol that simply did not trade.
        value =
            when {
                pos + neg <= 0.0 -> 50.0
                else -> 100.0 * pos / (pos + neg)
            }
    }
}

object MfiCalculator {
    /** MFI at the end of an oldest-first candle series, or null with fewer than [period] + 1 bars. */
    fun last(
        candles: List<Candle>,
        period: Int,
    ): Double? {
        if (period < 1) return null
        val mfi = MoneyFlow(period)
        candles.forEach { mfi.update(it.high, it.low, it.close, it.volume.toDouble()) }
        return mfi.value
    }
}
