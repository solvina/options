package cz.solvina.options.domain.features.strategy

import org.springframework.stereotype.Component

/**
 * The strategy library: every [StockStrategy] template Spring can find, keyed by [StockStrategy.id].
 *
 * Adding a strategy means adding one `@Component` — no registration list to forget, no switch in a
 * controller. The registry hands out **templates**; callers configure a run through
 * [StockStrategy.withParams] (see the statefulness note there).
 */
@Component
class StrategyRegistry(
    strategies: List<StockStrategy>,
) {
    private val byId: Map<String, StockStrategy>

    init {
        val duplicates = strategies.groupBy { it.id }.filterValues { it.size > 1 }.keys
        // Ids are persisted on runs (and later on assignments and live positions), so two strategies
        // answering to one id would silently reattribute history. Fail at startup, not at query time.
        require(duplicates.isEmpty()) { "Duplicate strategy ids: ${duplicates.sorted()}" }
        byId = strategies.associateBy { it.id }
    }

    fun all(): List<StockStrategy> = byId.values.sortedBy { it.displayName }

    fun find(id: String): StockStrategy? = byId[id]

    fun require(id: String): StockStrategy = find(id) ?: error("Unknown strategy '$id' — known: ${byId.keys.sorted()}")
}
