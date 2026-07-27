package cz.solvina.options.domain.features.bars

import java.time.Instant

/**
 * One OHLCV candle. Timeframe-agnostic: the same type carries 5-minute, 4-hour and daily bars —
 * the interval is a property of the *series* (see [Timeframe]), not of an individual candle, so it
 * is threaded through the ports rather than baked into this type.
 */
data class Candle(
    val time: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)
