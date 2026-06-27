package cz.solvina.options.domain.features.spread

import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.StrategyId
import org.springframework.stereotype.Component
import java.util.UUID

/** Resolves the [SpreadCloser] for a spread's strategy and aggregates loads across all strategies. */
@Component
class SpreadCloserRegistry(
    closers: List<SpreadCloser>,
) {
    private val byId: Map<StrategyId, SpreadCloser> = closers.associateBy { it.strategyId }

    fun forSpread(spread: Spread): SpreadCloser =
        byId[spread.strategyId] ?: error("No SpreadCloser registered for strategy ${spread.strategyId}")

    suspend fun findById(id: UUID): Spread? = byId.values.firstNotNullOfOrNull { it.findById(id) }

    suspend fun allOpen(): List<Spread> = byId.values.flatMap { it.openSpreads() }

    suspend fun allClosing(): List<Spread> = byId.values.flatMap { it.closingSpreads() }
}
