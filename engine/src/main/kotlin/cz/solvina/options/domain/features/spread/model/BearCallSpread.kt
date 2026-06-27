package cz.solvina.options.domain.features.spread.model

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Bear call credit spread (strategy #2). The mirror of a bull put: SELL the lower-strike call
 * ([soldLeg]) and BUY the higher-strike call ([boughtLeg]). Max risk = width − credit, same as a
 * bull put.
 *
 * [exDividendDate] backs US/American-style early-assignment protection — a short call carries
 * ex-dividend assignment risk that a short put does not. It is null for EU/European-style names
 * (no early assignment) and until the dividend data pipeline populates it.
 */
data class BearCallSpread(
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
    val exDividendDate: LocalDate? = null,
    override val strategyId: StrategyId = StrategyId.BEAR_CALL,
) : Spread
