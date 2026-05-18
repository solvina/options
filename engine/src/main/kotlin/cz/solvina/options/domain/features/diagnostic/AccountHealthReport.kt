package cz.solvina.options.domain.features.diagnostic

import java.math.BigDecimal
import java.time.Instant

data class AccountHealthReport(
    val probedAt: Instant,
    val netLiquidation: BigDecimal?,
    val availableFunds: BigDecimal?,
    val accountError: String?,
    val positionCount: Int,
    val positionsError: String?,
    val openOrderCount: Int,
    val openOrdersError: String?,
)
