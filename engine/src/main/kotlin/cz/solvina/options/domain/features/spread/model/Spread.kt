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
    val maxRiskPerShare: BigDecimal
    val quantity: Int
    val status: SpreadStatus
    val openedAt: Instant
    val strategyId: StrategyId
}
