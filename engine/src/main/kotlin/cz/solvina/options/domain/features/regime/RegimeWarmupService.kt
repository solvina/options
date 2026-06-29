package cz.solvina.options.domain.features.regime

import cz.solvina.options.domain.features.universe.UniversePort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

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
    @Scheduled(initialDelay = 600_000, fixedDelay = Long.MAX_VALUE)
    fun warmAtStartup() = runBlocking { warm() }

    /** Daily pre-market refresh. */
    @Scheduled(cron = "0 0 7 * * *")
    fun warmDaily() = runBlocking { warm() }

    suspend fun warm() {
        val symbols = universePort.getWatchlist()
        logger.info { "[REGIME WARMUP] computing regimes for ${symbols.size} symbols" }
        for (symbol in symbols) {
            runCatching {
                val r = regimeService.refresh(symbol)
                logger.info { "[REGIME] $symbol = ${r.regime} (close=${r.lastClose} smaFast=${r.smaFast} smaSlow=${r.smaSlow})" }
            }.onFailure { e -> logger.warn { "[REGIME WARMUP] $symbol failed: ${e.message}" } }
        }
        logger.info { "[REGIME WARMUP] done" }
    }
}
