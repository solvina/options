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
    // Reject when the long leg lands further than this multiple of the target width from the short
    // (the next listed strike can be far beyond target: QCOM 145/130 and CRM 265/280 on a $5 target).
    val maxWidthMultiple: Double = 2.0,
    // Reject when the achievable combo credit (BAG bid, or leg cross) is below this fraction of the
    // mid credit: the market is too wide for the mid to mean anything (CRM: 1.20 vs 2.61 = 46%).
    val minComboBidPctOfMid: Double = 0.60,
    // ---- Exit rules ----
    val takeProfitPercent: Double,
    val stopLossPercent: Double,
    // Underlying stop: close once the underlying trades through the short strike — at or below it
    // for a short put, at or above it for a short call. The buffer moves the trigger to the safe
    // side of the strike by that fraction of it (0.02 on a 230 put = 234.60); 0 = at the strike.
    // Spot has none of the option-mid noise that made the value stop fire on TXN (2026-07-17).
    val underlyingStopEnabled: Boolean = false,
    val underlyingStopBufferPct: Double = 0.0,
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
