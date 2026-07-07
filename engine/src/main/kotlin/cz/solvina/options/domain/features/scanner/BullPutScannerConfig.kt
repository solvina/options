package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.OptionType
import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/**
 * Bull put strategy parameters (`scanner.bull-put.*`) — the original strategy, now in its own
 * namespace symmetric with [BearCallScannerConfig] (`scanner.bear-call.*`). Everything that is
 * genuinely shared across strategies (portfolio cap, execution mechanics, IV cache, schedulers,
 * kill switches) stays in [ScannerConfig] (`scanner.*`).
 *
 * Defaults mirror the historical bull-put [ScannerConfig] defaults so nothing shifts if a key is
 * ever missing; the production values are set in `application.yml` under `scanner.bull-put`.
 */
@ConfigurationProperties("scanner.bull-put")
data class BullPutScannerConfig(
    // Entry filters
    val ivRankThreshold: Double = 30.0,
    val minDte: Int = 30,
    val maxDte: Int = 50,
    val preferredDte: Int = 45,
    // Delta selection — IBKR returns negative put deltas; targetDelta is magnitude
    val targetDelta: Double = 0.15,
    val deltaMin: Double = 0.10,
    val deltaMax: Double = 0.20,
    // Strike search
    val strikeBandPercent: Double = 0.20,
    val candidateStrikeCount: Int = 7,
    // Spread construction
    val spreadWidthUsd: BigDecimal = BigDecimal("5.0"),
    // Entry risk filters
    val minCreditPerShare: BigDecimal = BigDecimal("0.30"),
    val maxCreditPctOfWidth: Double = 0.40,
    val maxRiskPercent: Double = 0.025,
    // Exit rules
    val takeProfitPercent: Double = 0.50,
    val stopLossPercent: Double = 0.50,
    val timeProfitDte: Int = 14,
    // Execution
    val driftProtectionPct: Double = 0.01,
) : StrategyParamsProvider {
    override fun strategyParams() =
        StrategyParams(
            strategyId = StrategyId.BULL_PUT,
            optionType = OptionType.PUT,
            ivRankThreshold = ivRankThreshold,
            minDte = minDte,
            maxDte = maxDte,
            preferredDte = preferredDte,
            targetDelta = targetDelta,
            deltaMin = deltaMin,
            deltaMax = deltaMax,
            strikeBandPercent = strikeBandPercent,
            candidateStrikeCount = candidateStrikeCount,
            spreadWidthUsd = spreadWidthUsd,
            minCreditPerShare = minCreditPerShare,
            maxCreditPctOfWidth = maxCreditPctOfWidth,
            maxRiskPercent = maxRiskPercent,
            takeProfitPercent = takeProfitPercent,
            stopLossPercent = stopLossPercent,
            timeProfitDte = timeProfitDte,
            driftProtectionPct = driftProtectionPct,
        )
}
