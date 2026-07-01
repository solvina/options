package cz.solvina.options.domain.features.notification

import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/**
 * A qualified spread entry candidate — everything the scanner considered at the moment it decided a
 * symbol is tradeable, captured for outbound notification (email). This is built at the candidate
 * point inside the candidate selectors, where the full per-leg quotes/greeks are still in scope
 * (the downstream [cz.solvina.options.domain.features.execution.model.TradeExecutionRequest] is
 * lossy and drops the greeks). Notification-only: constructing this has no effect on trading.
 */
data class SpreadOpportunity(
    val strategyId: StrategyId,
    val symbol: Symbol,
    val session: String,
    val detectedAt: Instant,
    // ---- Entry filters that qualified the candidate ----
    val ivRank: Double,
    val ivRankThreshold: Double,
    val expiry: LocalDate,
    val dte: Int,
    val minDte: Int,
    val maxDte: Int,
    val preferredDte: Int,
    val targetDelta: Double,
    val deltaMin: Double,
    val deltaMax: Double,
    val underlyingPrice: BigDecimal,
    // ---- The two legs (full quotes: strike, bid/ask/mid, delta/gamma/theta/vega/iv) ----
    val shortLeg: OptionQuote,
    val longLeg: OptionQuote,
    // ---- Pricing & risk ----
    val midCredit: BigDecimal,
    val bidCredit: BigDecimal,
    val minCreditPerShare: BigDecimal,
    val targetWidth: BigDecimal,
    val actualWidth: BigDecimal,
    val maxRiskPerShare: BigDecimal,
    // ---- Money management ----
    val totalCapital: BigDecimal,
    val maxRiskPercent: Double,
    val quantity: Int,
    // ---- Planned management (config-driven exits) ----
    val takeProfitPercent: Double,
    val stopLossPercent: Double,
    val timeProfitDte: Int,
    val driftProtectionPct: Double,
) {
    val isBullPut: Boolean get() = strategyId == StrategyId.BULL_PUT

    /** Human label: "Bull Put" / "Bear Call". */
    val strategyLabel: String get() = if (isBullPut) "Bull Put" else "Bear Call"

    /** Bull put loses below (short strike − credit); bear call above (short strike + credit). */
    val breakEven: BigDecimal
        get() =
            (
                if (isBullPut) {
                    shortLeg.contract.strike.subtract(midCredit)
                } else {
                    shortLeg.contract.strike.add(midCredit)
                }
            ).setScale(2, RoundingMode.HALF_UP)

    val maxProfitPerContract: BigDecimal
        get() = midCredit.multiply(BigDecimal("100")).multiply(BigDecimal(quantity)).setScale(2, RoundingMode.HALF_UP)

    val maxLossPerContract: BigDecimal
        get() = maxRiskPerShare.multiply(BigDecimal("100")).multiply(BigDecimal(quantity)).setScale(2, RoundingMode.HALF_UP)

    val allowedRiskPerTrade: BigDecimal
        get() = totalCapital.multiply(BigDecimal(maxRiskPercent)).setScale(2, RoundingMode.HALF_UP)
}
