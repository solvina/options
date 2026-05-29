package cz.solvina.options.domain.features.bars

/** Wilder's Average True Range over the last [period] completed 5-minute candles. */
object AtrCalculator {
    fun atr(
        bars: List<FiveMinuteBar>,
        period: Int = 14,
    ): Double {
        if (bars.size < period + 1) return Double.NaN
        val slice = bars.takeLast(period + 1)

        // True ranges
        val trues =
            (1..slice.lastIndex).map { i ->
                val cur = slice[i]
                val prev = slice[i - 1]
                maxOf(
                    cur.high - cur.low,
                    Math.abs(cur.high - prev.close),
                    Math.abs(cur.low - prev.close),
                )
            }

        return trues.average()
    }
}
