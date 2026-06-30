package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.OptionType
import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/**
 * Bear call strategy parameters (`scanner.bear-call.*`) — a separate namespace from the bull-put
 * [ScannerConfig] per the naming mandate, so the two strategies tune independently.
 *
 * Starts [enabled] = false: the scanner only runs bear call once it is explicitly turned on (and,
 * per the plan, the dividend-data pipeline is live). [minCreditPerShare] is the real-market credit
 * floor — there is deliberately no "skew adjustment"; any higher bar for bear calls is expressed
 * here, applied to the actual quoted credit.
 */
@ConfigurationProperties("scanner.bear-call")
data class BearCallScannerConfig(
    val enabled: Boolean = false,
    val ivRankThreshold: Double = 45.0,
    val minDte: Int = 30,
    val maxDte: Int = 50,
    val preferredDte: Int = 45,
    val targetDelta: Double = 0.30,
    val deltaMin: Double = 0.25,
    val deltaMax: Double = 0.30,
    // Strike search band (mirrors the bull-put ScannerConfig); the chain fetch flips it ABOVE the
    // underlying for calls. Defaulted to the bull-put values so behaviour is unchanged until tuned.
    val strikeBandPercent: Double = 0.20,
    val candidateStrikeCount: Int = 7,
    val spreadWidthUsd: BigDecimal = BigDecimal("5.0"),
    val minCreditPerShare: BigDecimal = BigDecimal("0.40"),
    val maxRiskPercent: Double = 0.025,
    val takeProfitPercent: Double = 0.50,
    val stopLossPercent: Double = 2.00,
    val timeProfitDte: Int = 21,
    val driftProtectionPct: Double = 0.05,
    // Dividend assignment protection (US/American-style only; wired in Phase 3 with the data pipeline)
    val dividendCheckWindowHours: Long = 48,
    val exDividendEntryBufferHours: Long = 48,
) : StrategyParamsProvider {
    override fun strategyParams() =
        StrategyParams(
            strategyId = StrategyId.BEAR_CALL,
            optionType = OptionType.CALL,
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
            maxRiskPercent = maxRiskPercent,
            takeProfitPercent = takeProfitPercent,
            stopLossPercent = stopLossPercent,
            timeProfitDte = timeProfitDte,
            driftProtectionPct = driftProtectionPct,
        )
}
