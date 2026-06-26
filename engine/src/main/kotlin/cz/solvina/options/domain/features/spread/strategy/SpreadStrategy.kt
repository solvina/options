package cz.solvina.options.domain.features.spread.strategy

import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.spread.model.StrategyId

/**
 * The seam where per-strategy behaviour lives — one implementation per [StrategyId].
 *
 * The generic core never branches on the concrete spread type. It resolves the owning strategy via
 * [SpreadStrategyRegistry] and delegates. Adding a new strategy means adding an implementation and
 * registering it as a bean; the core is untouched.
 */
interface SpreadStrategy {
    val id: StrategyId

    /**
     * A strategy-specific exit, evaluated after the shared TP/SL/DTE rules (e.g. bear-call
     * dividend-assignment protection). Returns null when the strategy adds no exit of its own.
     */
    suspend fun strategyExitSignal(spread: Spread): StrategyExit? = null
}

/** A close decision contributed by a [SpreadStrategy] beyond the generic exit rules. */
data class StrategyExit(
    val status: SpreadStatus,
    val reason: String,
)
