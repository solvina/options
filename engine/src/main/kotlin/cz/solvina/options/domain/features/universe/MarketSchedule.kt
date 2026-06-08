package cz.solvina.options.domain.features.universe

import java.time.LocalTime
import java.time.ZoneId

data class MarketSchedule(
    val zone: ZoneId,
    val open: LocalTime,
    val close: LocalTime,
    val session: String,
)
