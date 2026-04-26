package cz.solvina.options.domain.models

import java.math.BigDecimal
import java.time.LocalDate

data class OptionContract(
    val symbol: Symbol,
    val expiry: LocalDate,
    val strike: BigDecimal,
    val type: OptionType,
)
