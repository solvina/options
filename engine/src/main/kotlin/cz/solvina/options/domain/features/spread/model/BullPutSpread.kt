package cz.solvina.options.domain.features.spread.model

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class BullPutSpread(
    val id: UUID?,
    val symbol: Symbol,
    val soldLeg: SpreadLeg,
    val boughtLeg: SpreadLeg,
    val creditPerShare: BigDecimal,
    val maxRiskPerShare: BigDecimal,
    val quantity: Int = 1,
    val status: SpreadStatus,
    val ivRankAtEntry: Double?,
    val underlyingPriceAtEntry: BigDecimal?,
    val openedAt: Instant,
    val closedAt: Instant? = null,
    val closeReason: String? = null,
    val closePricePerShare: BigDecimal? = null,
)
