package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.OptionType
import java.math.BigDecimal

/**
 * Per-strategy tuning, resolved by [StrategyId] via [StrategyParamsRegistry].
 *
 * The strategy-agnostic core — candidate selection, the option-chain fetch, trade execution and
 * spread management — must apply *the owning strategy's* params, not a single hardcoded config.
 * Each strategy's `@ConfigurationProperties` exposes one of these via [StrategyParamsProvider];
 * the bull-put params live at `scanner.*` (see [ScannerConfig]) and bear-call at `scanner.bear-call.*`
 * (see [cz.solvina.options.domain.features.scanner.BearCallScannerConfig]).
 *
 * [optionType] is the discriminator the chain fetch needs (PUT for bull put, CALL for bear call);
 * without it the chain only ever returned puts, so bear-call could never select a leg.
 */
data class StrategyParams(
    val strategyId: StrategyId,
    val optionType: OptionType,
    // ---- Entry selection ----
    val ivRankThreshold: Double,
    val minDte: Int,
    val maxDte: Int,
    val preferredDte: Int,
    val targetDelta: Double,
    val deltaMin: Double,
    val deltaMax: Double,
    val strikeBandPercent: Double,
    val candidateStrikeCount: Int,
    val spreadWidthUsd: BigDecimal,
    val minCreditPerShare: BigDecimal,
    // Crash-pricing guard: reject candidates whose MID credit exceeds this fraction of the actual
    // width. A true ~30-delta spread prices ~15–30% of width; 45%+ means the market prices roughly
    // even odds of finishing ITM regardless of what the (vol-spike-distorted) greeks claim — NBIS
    // sold at 49.5% of width and a BE candidate hit 90% during the 2026-07-06/07 spike.
    val maxCreditPctOfWidth: Double,
    val maxRiskPercent: Double,
    // ---- Exit rules ----
    val takeProfitPercent: Double,
    val stopLossPercent: Double,
    val timeProfitDte: Int,
    // ---- Execution ----
    val driftProtectionPct: Double,
)

/**
 * Implemented by each strategy's config bean so [StrategyParamsRegistry] can collect them the same
 * way [cz.solvina.options.domain.features.spread.SpreadCloserRegistry] collects closers. Adding a
 * third strategy means adding a config that implements this — the core is untouched.
 */
interface StrategyParamsProvider {
    fun strategyParams(): StrategyParams
}
