package cz.solvina.options.domain.features.bars

object VolumeAnalysis {
    /** Simple moving average of volume over the last [period] candles. */
    fun volumeMa(
        bars: List<FiveMinuteBar>,
        period: Int = 20,
    ): Double {
        if (bars.size < period) return Double.NaN
        return bars.takeLast(period).map { it.volume.toDouble() }.average()
    }
}
