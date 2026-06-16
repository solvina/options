package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Pre-computes IV rank for the whole spread universe right after startup, so the first scans don't
 * block on cold 365-day history fetches. Work is:
 *  - **prioritised** — symbols whose market is currently open are warmed first;
 *  - **batched** — processed in [ScannerConfig.warmupBatchSize] chunks, with the per-batch concurrency
 *    further bounded by the IBKR rate limiter's historical in-flight cap;
 *  - **isolated** — a symbol that fails to warm (bad ticker, no IV data) is logged and skipped, never
 *    aborting the rest.
 *
 * IV ranks already warm-loaded from the persistent store (Phase 2) return immediately, so this only
 * does real IBKR work for symbols that are genuinely cold or stale.
 */
@Service
class UniverseWarmupService(
    private val universePort: UniversePort,
    private val volatilityPort: VolatilityPort,
    private val config: ScannerConfig,
    private val scope: CoroutineScope,
) {
    @Volatile
    private var _lastResult: WarmupResult? = null
    val lastResult: WarmupResult? get() = _lastResult

    data class WarmupResult(
        val total: Int,
        val warmed: Int,
        val failed: Int,
        val done: Boolean,
    )

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        scope.launch { warmAll() }
    }

    suspend fun warmAll() {
        val symbols =
            universePort
                .getWatchlist()
                .sortedByDescending { universePort.isMarketOpen(it) } // open-market symbols first

        if (symbols.isEmpty()) {
            logger.info { "Universe warmup: nothing to warm (empty watchlist)" }
            _lastResult = WarmupResult(0, 0, 0, done = true)
            return
        }

        logger.info { "Universe warmup: pre-computing IV rank for ${symbols.size} symbols (open-market first)" }
        val warmed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        _lastResult = WarmupResult(symbols.size, 0, 0, done = false)

        symbols.chunked(config.warmupBatchSize.coerceAtLeast(1)).forEach { batch ->
            batch
                .map { symbol ->
                    scope.async { warmOne(symbol, warmed, failed) }
                }.awaitAll()
            _lastResult = WarmupResult(symbols.size, warmed.get(), failed.get(), done = false)
        }

        val result = WarmupResult(symbols.size, warmed.get(), failed.get(), done = true)
        _lastResult = result
        logger.info { "Universe warmup complete: ${result.warmed} warmed, ${result.failed} failed of ${result.total}" }
    }

    private suspend fun warmOne(
        symbol: Symbol,
        warmed: AtomicInteger,
        failed: AtomicInteger,
    ) {
        runCatching { volatilityPort.getIvRank(symbol) }
            .onSuccess { warmed.incrementAndGet() }
            .onFailure {
                failed.incrementAndGet()
                logger.debug { "[$symbol] warmup IV-rank failed (skipped): ${it.message}" }
            }
    }
}
