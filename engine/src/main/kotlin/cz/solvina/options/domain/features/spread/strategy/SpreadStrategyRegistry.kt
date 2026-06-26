package cz.solvina.options.domain.features.spread.strategy

import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.StrategyId
import org.springframework.stereotype.Component

/**
 * Resolves the [SpreadStrategy] that owns a given spread, by [StrategyId]. Spring injects every
 * registered [SpreadStrategy] bean, so the core never references a concrete strategy directly.
 */
@Component
class SpreadStrategyRegistry(
    strategies: List<SpreadStrategy>,
) {
    private val byId: Map<StrategyId, SpreadStrategy> = strategies.associateBy { it.id }

    fun forSpread(spread: Spread): SpreadStrategy? = byId[spread.strategyId]
}
