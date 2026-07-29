package cz.solvina.options.domain.features.strategy.tuning

import cz.solvina.options.domain.features.strategy.TunableStrategy
import org.springframework.stereotype.Component

/**
 * Every [TunableStrategy] Spring can find, keyed by id.
 *
 * Mirrors [cz.solvina.options.domain.features.strategy.StrategyRegistry] but is deliberately wider:
 * that one holds only stock strategies (things that can [cz.solvina.options.domain.features.strategy.StockStrategy.decide]),
 * while this one holds everything with tunable parameters — including the flag strategy, which is a
 * live service rather than a library strategy. The tuning API and its generic UI form read from here,
 * so a new strategy appears in the UI by existing, with no page to edit.
 */
@Component
class TunableStrategyRegistry(
    strategies: List<TunableStrategy>,
) {
    private val byId: Map<String, TunableStrategy>

    init {
        val duplicates = strategies.groupBy { it.id }.filterValues { it.size > 1 }.keys
        // Ids key the persisted override rows. Two strategies answering to one id would have them
        // silently share tuning. Fail at startup, not when someone wonders why a save had no effect.
        require(duplicates.isEmpty()) { "Duplicate tunable strategy ids: ${duplicates.sorted()}" }
        byId = strategies.associateBy { it.id }
    }

    fun all(): List<TunableStrategy> = byId.values.sortedBy { it.displayName }

    fun find(id: String): TunableStrategy? = byId[id]

    fun require(id: String): TunableStrategy = find(id) ?: error("Unknown tunable strategy '$id' — known: ${byId.keys.sorted()}")
}
