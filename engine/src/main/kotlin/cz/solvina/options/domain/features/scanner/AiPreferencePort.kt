package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.Symbol

/** The other strategy the AI reviewer would rather sell on a symbol today, if any (advisory). */
fun interface AiPreferencePort {
    suspend fun preferredSwitch(symbol: Symbol): StrategyId?

    companion object {
        val NoOp = AiPreferencePort { null }
    }
}
