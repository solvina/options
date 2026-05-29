package cz.solvina.options.domain.features.scanner

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

@ConfigurationProperties("scanner")
data class ScannerConfig(
    val watchlist: List<String> = emptyList(),
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
    val maxRiskPercent: Double = 0.025,
    val maxOpenSpreads: Int = 5,
    // Fees: IBKR charges per contract; 2 legs × feePerContract / 100 = fee per share
    val feePerContract: BigDecimal = BigDecimal("0.65"),
    // Exit rules
    val takeProfitPercent: Double = 0.50,
    val stopLossPercent: Double = 0.50,
    val timeProfitDte: Int = 14,
    // Trade execution (tick-by-tick price improvement)
    val driftProtectionPct: Double = 0.01,
    val executionTimeoutMinutes: Long = 15,
    val ticksBeforePriceAdjust: Int = 5,
    val priceAdjustIntervalSeconds: Int = 30,
    val maxLegBidAskSpreadPct: Double = 0.30,
    // Order chase (used by SpreadManagementService close orders)
    val orderChaseTimeoutMinutes: Long = 5,
    val orderChaseMaxRetries: Int = 3,
    val orderChasePriceStep: Double = 0.03,
    // Entry cooldown — how long to wait before retrying a symbol after a failed entry
    val entryCooldownMinutes: Long = 240,
    // Schedulers
    val cron: String = "0 */15 10-15 * * MON-FRI",
    val monitorDelayMs: Long = 60_000,
    // Kill switches (can be overridden at runtime via API)
    val scannerPaused: Boolean = false,
    val monitorPaused: Boolean = false,
    val tradingEnabled: Boolean = true,
    // Cache TTLs
    val ivHistoryDays: Int = 365,
    val ivCacheTtlMinutes: Long = 60,
    val optionParamsCacheTtlHours: Long = 24,
)
