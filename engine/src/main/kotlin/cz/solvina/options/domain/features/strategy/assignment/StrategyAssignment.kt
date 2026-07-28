package cz.solvina.options.domain.features.strategy.assignment

import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import java.time.Instant
import java.util.UUID

/**
 * The managed layer of the strategy platform: which [strategyId] runs on which [symbol], at which
 * [timeframe], with which parameter overrides.
 *
 * [paramOverrides] is null when nothing has been overridden — the strategy's descriptor defaults
 * apply in full. Resolution is overrides-over-defaults, the same shape spreads already use
 * (`inst?.ivRankThreshold ?: config.ivRankThreshold`) generalised to a params blob.
 *
 * [enabled] is the live on/off switch. It gates the runner only; a disabled assignment is still
 * backtestable and still owns any historical positions attributed to it.
 */
data class StrategyAssignment(
    val id: UUID,
    val strategyId: String,
    val symbol: Symbol,
    val timeframe: Timeframe,
    val paramOverrides: Map<String, Any?>?,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
