package cz.solvina.options.domain.features.bars

import java.time.Instant

data class RealTimeBar(
    val time: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val wap: Double = Double.NaN,
)
