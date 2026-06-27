package cz.solvina.options.domain.features.execution

import cz.solvina.options.domain.features.spread.model.StrategyId
import org.springframework.stereotype.Component

/** Resolves the [SpreadEntryWriter] for a strategy. Spring injects every registered writer bean. */
@Component
class SpreadEntryWriterRegistry(
    writers: List<SpreadEntryWriter>,
) {
    private val byId: Map<StrategyId, SpreadEntryWriter> = writers.associateBy { it.strategyId }

    fun forStrategy(strategyId: StrategyId): SpreadEntryWriter =
        byId[strategyId] ?: error("No SpreadEntryWriter registered for strategy $strategyId")
}
