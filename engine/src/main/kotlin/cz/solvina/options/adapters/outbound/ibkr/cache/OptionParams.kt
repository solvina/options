package cz.solvina.options.adapters.outbound.ibkr.cache

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class OptionParams(
    val expirations: Set<LocalDate>,
    val strikes: Set<BigDecimal>,
    val strikesByExpiry: Map<LocalDate, Set<BigDecimal>>,
    val fetchedAt: Instant,
    val exchange: String = "SMART",
    val tradingClass: String = "",
    val multiplier: String = "100",
)
