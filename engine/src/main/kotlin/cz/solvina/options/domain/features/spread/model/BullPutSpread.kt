package cz.solvina.options.domain.features.spread.model

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class BullPutSpread(
    override val id: UUID?,
    override val symbol: Symbol,
    override val soldLeg: SpreadLeg,
    override val boughtLeg: SpreadLeg,
    override val creditPerShare: BigDecimal,
    override val maxRiskPerShare: BigDecimal,
    override val quantity: Int = 1,
    override val status: SpreadStatus,
    val ivRankAtEntry: Double?,
    val underlyingPriceAtEntry: BigDecimal?,
    override val openedAt: Instant,
    val closedAt: Instant? = null,
    val closeReason: String? = null,
    val closePricePerShare: BigDecimal? = null,
    val lastSpreadValue: BigDecimal? = null,
    val underlyingPriceAtExit: BigDecimal? = null,
    val ivRankAtExit: BigDecimal? = null,
    override val strategyId: StrategyId = StrategyId.BULL_PUT,
) : Spread
