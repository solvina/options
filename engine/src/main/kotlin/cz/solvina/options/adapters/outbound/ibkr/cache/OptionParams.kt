package cz.solvina.options.adapters.outbound.ibkr.cache

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class OptionParams(
    val expirations: Set<LocalDate>,
    val strikes: Set<BigDecimal>,
    val fetchedAt: Instant,
)
