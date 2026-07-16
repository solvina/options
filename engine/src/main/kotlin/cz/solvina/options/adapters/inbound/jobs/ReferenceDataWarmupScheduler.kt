package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrOptionParamsCache
import cz.solvina.options.domain.features.market.MarketDataPriority
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.scanner.BullPutScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

/**
 * Pre-warms the slow-changing option reference data — IV rank, option params (expirations/strikes),
 * and verified strikes + conIds for the near-target expiry — so the first scan after the US open
 * doesn't do all that cold IBKR work at once (the cause of the ~22-min open scan). Runs on a
 * pre-open schedule AND after every restart; both funnel through the same per-item, restart-safe
 * refresh guard so redundant work is skipped.
 *
 * Idempotency ("update each item at most once a day"):
 *  - IV rank is protected by its own persistent store + TTL/stale-window (warm-loaded on startup,
 *    served-stale-and-refreshed rather than re-fetched from scratch).
 *  - Option params are persisted + warm-loaded, and skipped here when the warm value is younger than
 *    [refreshIntervalHours] — so a restart within the window reuses the DB copy instead of re-fetching.
 *  - Verified strikes are cached in memory until expiry; the fetch is a no-op once warmed for the day.
 *
 * Never touches live data (greeks/quotes/prices) — those are always fetched fresh at scan time.
 */
@Component
class ReferenceDataWarmupScheduler(
    private val universePort: UniversePort,
    private val volatilityPort: VolatilityPort,
    private val optionParamsCache: IbkrOptionParamsCache,
    private val contractCache: IbkrContractCache,
    private val bullPut: BullPutScannerConfig,
    private val bearCall: BearCallScannerConfig,
    private val scannerConfig: ScannerConfig,
    private val clock: Clock,
    @Value("\${reference-warmup.enabled:true}") private val enabled: Boolean,
    @Value("\${reference-warmup.refresh-interval-hours:20}") private val refreshIntervalHours: Long,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    /** Pre-open daily warmup. Cron is Europe/Berlin (the RPi's zone); 14:30 sits before the 15:30 US open. */
    @Scheduled(cron = "\${reference-warmup.cron:0 30 14 * * MON-FRI}", zone = "Europe/Berlin")
    fun scheduledWarmup() = trigger("pre-open schedule")

    /** After every restart — warm from IBKR whatever the DB warm-load didn't already make fresh. */
    @EventListener(ApplicationReadyEvent::class)
    fun onReady() = trigger("startup")

    private fun trigger(reason: String) {
        if (!enabled) {
            logger.debug { "Reference-data warmup skipped ($reason): disabled" }
            return
        }
        scope.launch {
            if (!mutex.tryLock()) {
                logger.debug { "Reference-data warmup skipped ($reason): a run is already in progress" }
                return@launch
            }
            try {
                runCatching { warmAll(reason) }
                    .onFailure { e -> logger.error(e) { "Reference-data warmup failed ($reason): ${e.message}" } }
            } finally {
                mutex.unlock()
            }
        }
    }

    private suspend fun warmAll(reason: String) =
        withContext(MarketDataPriority.SCANNER) {
            val symbols =
                universePort
                    .getActiveSymbols()
                    .sortedByDescending { universePort.isMarketOpen(it) }
            if (symbols.isEmpty()) {
                logger.info { "Reference-data warmup ($reason): empty universe, nothing to warm" }
                return@withContext
            }
            logger.info { "Reference-data warmup ($reason): warming ${symbols.size} symbols" }
            val batch = scannerConfig.warmupBatchSize.coerceAtLeast(1)
            symbols.chunked(batch).forEach { chunk ->
                // coroutineScope (not the IO scope) so children inherit the SCANNER market-data priority.
                coroutineScope { chunk.map { async { warmOne(it) } }.awaitAll() }
            }
            logger.info { "Reference-data warmup ($reason): complete" }
        }

    private suspend fun warmOne(symbol: Symbol) {
        // 1. IV rank — self-guarding via its persistent store + TTL/stale-window.
        runCatching { volatilityPort.getIvRank(symbol) }
            .onFailure { logger.debug { "[$symbol] warmup IV-rank skipped: ${it.message}" } }

        // 2. Option params — reuse the warm-loaded DB value if it's younger than the daily interval;
        //    otherwise re-fetch (which also re-persists via the cache's write-through).
        val cached = optionParamsCache.getCached(symbol)
        val paramsFresh =
            cached != null && Duration.between(cached.fetchedAt, Instant.now(clock)) < Duration.ofHours(refreshIntervalHours)
        val params =
            if (paramsFresh) {
                cached
            } else {
                runCatching { optionParamsCache.getOrFetch(symbol) }
                    .getOrElse {
                        logger.debug { "[$symbol] warmup option-params skipped: ${it.message}" }
                        cached
                    }
            }

        // 3. Verified strikes + conIds for the near-target expiry (PUT always; CALL when bear-call is on).
        if (params == null || params.expirations.isEmpty()) return
        nearestExpiry(params.expirations, bullPut.minDte, bullPut.maxDte, bullPut.preferredDte)?.let { exp ->
            runCatching { contractCache.getOrFetchVerifiedStrikes(symbol, exp, OptionType.PUT) }
                .onFailure { logger.debug { "[$symbol] warmup verified-strikes (PUT) skipped: ${it.message}" } }
        }
        if (bearCall.enabled) {
            nearestExpiry(params.expirations, bearCall.minDte, bearCall.maxDte, bearCall.preferredDte)?.let { exp ->
                runCatching { contractCache.getOrFetchVerifiedStrikes(symbol, exp, OptionType.CALL) }
                    .onFailure { logger.debug { "[$symbol] warmup verified-strikes (CALL) skipped: ${it.message}" } }
            }
        }
    }

    private fun nearestExpiry(
        expirations: Set<LocalDate>,
        minDte: Int,
        maxDte: Int,
        preferredDte: Int,
    ): LocalDate? {
        val today = LocalDate.now(clock)
        return expirations
            .filter { ChronoUnit.DAYS.between(today, it).toInt() in minDte..maxDte }
            .minByOrNull { abs(ChronoUnit.DAYS.between(today, it).toInt() - preferredDte) }
    }
}
