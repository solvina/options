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
    override val ivRankAtEntry: Double?,
    override val underlyingPriceAtEntry: BigDecimal?,
    override val openedAt: Instant,
    override val closedAt: Instant? = null,
    override val closeReason: String? = null,
    override val closePricePerShare: BigDecimal? = null,
    override val lastSpreadValue: BigDecimal? = null,
    override val underlyingPriceAtExit: BigDecimal? = null,
    override val ivRankAtExit: BigDecimal? = null,
    override val strategyId: StrategyId = StrategyId.BULL_PUT,
) : Spread
