package cz.solvina.options.domain.models

import java.math.BigDecimal
import java.time.LocalDate

data class HistoricalBar(
    val date: LocalDate,
    val close: BigDecimal,
    val iv: Double?,
)
