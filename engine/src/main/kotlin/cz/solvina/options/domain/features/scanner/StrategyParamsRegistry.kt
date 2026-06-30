package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.spread.model.StrategyId
import org.springframework.stereotype.Component

/** Resolves the [StrategyParams] for a given [StrategyId], collected from every strategy's config. */
@Component
class StrategyParamsRegistry(
    providers: List<StrategyParamsProvider>,
) {
    private val byId: Map<StrategyId, StrategyParams> =
        providers.map { it.strategyParams() }.associateBy { it.strategyId }

    fun forStrategy(id: StrategyId): StrategyParams = byId[id] ?: error("No StrategyParams registered for strategy $id")
}
