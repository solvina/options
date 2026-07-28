package cz.solvina.options.domain.features.regime

import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.shared.scheduling.runScheduled
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.hours

private val logger = KotlinLogging.logger {}

/**
 * Populates the regime cache off the trading/scan path: fetches price history and classifies each
 * watchlist symbol on a schedule, sequentially (the IBKR historical rate limiter paces it). The
 * scanner then only reads the cache, so regime adds zero historical load to the scan and never
 * competes with the trading-critical IV-rank fetches. Also logs a daily market-regime snapshot.
 */
@Component
class RegimeWarmupService(
    private val regimeService: TrendRegimeService,
    private val universePort: UniversePort,
) {
    /** One-shot after startup (delayed so the IV-rank warmup finishes first). */
    @Scheduled(initialDelay = 600_000, fixedDelay = Long.MAX_VALUE, scheduler = "backgroundTaskScheduler")
    fun warmAtStartup() = runScheduled("Regime warmup (startup)") { warm() }

    /** Daily pre-market refresh. */
    @Scheduled(cron = "0 0 7 * * *", scheduler = "backgroundTaskScheduler")
    fun warmDaily() = runScheduled("Regime warmup (daily)", expectedPeriod = 24.hours) { warm() }

    suspend fun warm() {
        val symbols = universePort.getWatchlist()
        logger.info { "[REGIME WARMUP] computing regimes for ${symbols.size} symbols" }
        for (symbol in symbols) {
            runCatching {
                val r = regimeService.refresh(symbol)
                logger.info {
                    "[REGIME] $symbol = ${r.regime} bias=${r.bias} rsi=${r.rsi} " +
                        "(close=${r.lastClose} smaFast=${r.smaFast} smaSlow=${r.smaSlow})"
                }
            }.onFailure { e -> logger.warn { "[REGIME WARMUP] $symbol failed: ${e.message}" } }
        }
        logger.info { "[REGIME WARMUP] done" }
    }
}
