package cz.solvina.options.adapters.outbound.ibkr.cache

import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.LocalDate

data class OptionContractKey(
    val symbol: Symbol,
    val expiry: LocalDate,
    val strike: BigDecimal,
    val optionType: OptionType,
)
