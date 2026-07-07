package cz.solvina.options.domain.features.spread.model

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Common shape of every two-leg credit spread, independent of strategy (bull put, bear call, …).
 *
 * The generic core — exit management, reconciliation, the portfolio cap, dashboards — operates on
 * this type. Anything that differs per strategy lives behind
 * [cz.solvina.options.domain.features.spread.strategy.SpreadStrategy], resolved by [strategyId].
 */
sealed interface Spread {
    val id: UUID?
    val symbol: Symbol
    val soldLeg: SpreadLeg
    val boughtLeg: SpreadLeg
    val creditPerShare: BigDecimal

    /**
     * Fair value (mid) of the spread at fill time. The stop-loss threshold is computed off this,
     * not [creditPerShare]: a fill below mid must not mechanically tighten the stop (a spread
     * filled at 50% of mid with a credit-based 3× stop starts life already at its stop — LITE
     * was stopped 29 s after entry this way). Null on rows persisted before 2026-07 (v24);
     * consumers fall back to [creditPerShare].
     */
    val entryMidPerShare: BigDecimal?
    val maxRiskPerShare: BigDecimal
    val quantity: Int
    val status: SpreadStatus
    val ivRankAtEntry: Double?
    val underlyingPriceAtEntry: BigDecimal?
    val openedAt: Instant
    val closedAt: Instant?
    val closeReason: String?
    val closePricePerShare: BigDecimal?
    val lastSpreadValue: BigDecimal?
    val underlyingPriceAtExit: BigDecimal?
    val ivRankAtExit: BigDecimal?
    val strategyId: StrategyId
}
