package cz.solvina.options.domain.features.bars

import java.time.Instant

/** A fully assembled 5-minute OHLCV candle built from exactly 60 × 5-second bars. */
data class FiveMinuteBar(
    val time: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)
