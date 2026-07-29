package cz.solvina.options.domain.features.strategy.assignment

import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import java.time.Instant
import java.util.UUID

/**
 * The managed layer of the strategy platform: which [strategyId] runs on which [symbol], at which
 * [timeframe].
 *
 * Parameters are NOT stored here. They live in the shared tuning tables
 * (`strategy_default_params` / `strategy_symbol_params`) and are resolved by
 * [cz.solvina.options.domain.features.strategy.tuning.StrategyParamsResolver], so one strategy is
 * tuned in one place whether or not it happens to have an assignment row — the flag strategy has
 * none, because its enablement is `instrument_universe.flag_enabled`.
 *
 * [enabled] is the live on/off switch. It gates the runner only; a disabled assignment is still
 * backtestable and still owns any historical positions attributed to it.
 */
data class StrategyAssignment(
    val id: UUID,
    val strategyId: String,
    val symbol: Symbol,
    val timeframe: Timeframe,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
