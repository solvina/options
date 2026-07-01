package cz.solvina.options.domain.features.scanner

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/**
 * Shared scanner / execution infrastructure (`scanner.*`) — everything NOT specific to one strategy:
 * the portfolio cap, execution mechanics, order chase, cooldowns, schedulers, kill switches and the
 * IV cache. Per-strategy tuning lives in [BullPutScannerConfig] (`scanner.bull-put.*`) and
 * [BearCallScannerConfig] (`scanner.bear-call.*`), resolved via [StrategyParamsRegistry].
 */
@ConfigurationProperties("scanner")
data class ScannerConfig(
    val watchlist: List<String> = emptyList(),
    // Shared portfolio cap across all strategies (bull put + bear call)
    val maxOpenSpreads: Int = 5,
    // Fees: IBKR charges per contract; 2 legs × feePerContract / contractMultiplier = fee per share
    val feePerContract: BigDecimal = BigDecimal("0.65"),
    // Shares per option contract (US equity options = 100); used to convert per-contract → per-share
    val contractMultiplier: BigDecimal = BigDecimal("100"),
    // Trade execution (tick-by-tick price improvement) — shared mechanics
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
    // Stop-loss cooldown — how long to block re-entry after a CLOSED_STOP
    val stopLossCooldownHours: Long = 24,
    // Schedulers
    // Cron uses server timezone (CEST, UTC+2)
    // EU markets: 9:00 AM - 6:30 PM CEST (Frankfurt DAX, Euronext)
    // US markets: 3:30 PM - 10:00 PM CEST (NYSE/NASDAQ = 9:30 AM - 4:00 PM EDT)
    // Run every 15 minutes, 9 AM - 10:59 PM CEST to cover both EU and US sessions
    // Spring cron format: seconds minutes hours day-of-month month day-of-week
    val cron: String = "0 */15 9-22 * * MON-FRI",
    val monitorDelayMs: Long = 60_000,
    // Kill switches (can be overridden at runtime via API)
    val scannerPaused: Boolean = false,
    val monitorPaused: Boolean = false,
    val tradingEnabled: Boolean = true,
    // Cache TTLs
    val ivHistoryDays: Int = 365,
    val ivCacheTtlMinutes: Long = 60,
    // After the cache TTL but within this window, a persisted IV rank is served stale while a
    // refresh runs in the background — avoids a restart re-fetching every symbol's history at once.
    val ivServeStaleHours: Long = 48,
    // Startup warmup: pre-compute IV rank for the universe in batches (open-market symbols first)
    // so scans never block on a cold fetch. Batch size bounds concurrency alongside the rate limiter.
    val warmupBatchSize: Int = 10,
    // Refresh every hour to catch underlying movement + strikes being descheduled
    val optionParamsCacheTtlHours: Long = 1,
)
