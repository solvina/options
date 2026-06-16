package cz.solvina.options.domain.features.volatility

import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.models.IvRank
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class IvRankService(
    private val histDataPort: HistoricalDataPort,
    private val config: ScannerConfig,
    private val clock: Clock,
    private val store: IvRankStorePort,
    private val scope: CoroutineScope,
) : VolatilityPort {
    private data class CachedIvRank(
        val ivRank: IvRank,
        val cachedAt: Instant,
    )

    private val cache = ConcurrentHashMap<Symbol, CachedIvRank>()

    // Symbols with a background refresh in flight, so we don't queue duplicates while serving stale.
    private val refreshing = ConcurrentHashMap.newKeySet<Symbol>()

    /** Warm the in-memory cache from the persistent store so the first scan after a restart does not
     *  re-fetch 365-day IV history for every symbol at once (which bursts IBKR historical pacing). */
    @EventListener(ApplicationReadyEvent::class)
    fun warmCache() {
        val loaded = store.loadAll()
        loaded.forEach { (symbol, v) ->
            cache[symbol] = CachedIvRank(IvRank(rank = v.rank, currentIv = v.currentIv, calculatedAt = v.calculatedAt), v.calculatedAt)
        }
        if (loaded.isNotEmpty()) logger.info { "IV-rank cache warm-loaded ${loaded.size} symbols from store" }
    }

    override suspend fun getIvRank(symbol: Symbol): IvRank {
        val now = Instant.now(clock)
        val cached = cache[symbol]
        val ttl = Duration.ofMinutes(config.ivCacheTtlMinutes)
        if (cached != null && now.isBefore(cached.cachedAt.plus(ttl))) {
            logger.debug { "[$symbol] IV Rank cache hit: ${cached.ivRank.rank}" }
            return cached.ivRank
        }

        // Serve-stale-while-revalidate: within the stale window, return the persisted/old value and
        // refresh in the background (paced by the rate limiter) instead of blocking the scan — and
        // instead of every symbol re-fetching synchronously at once after a restart.
        val staleWindow = Duration.ofHours(config.ivServeStaleHours)
        if (cached != null && now.isBefore(cached.cachedAt.plus(staleWindow))) {
            triggerBackgroundRefresh(symbol)
            logger.debug { "[$symbol] IV Rank serving stale (${cached.ivRank.rank}) — refresh queued" }
            return cached.ivRank
        }

        return computeAndCache(symbol)
    }

    private fun triggerBackgroundRefresh(symbol: Symbol) {
        if (!refreshing.add(symbol)) return
        scope.launch {
            try {
                computeAndCache(symbol)
            } catch (e: Exception) {
                logger.warn { "[$symbol] IV-rank background refresh failed: ${e.message}" }
            } finally {
                refreshing.remove(symbol)
            }
        }
    }

    private suspend fun computeAndCache(symbol: Symbol): IvRank {
        val bars = histDataPort.fetchDailyBars(symbol, config.ivHistoryDays).toList()
        val ivBars = bars.filter { it.iv != null }
        check(ivBars.isNotEmpty()) { "No IV data for $symbol over ${config.ivHistoryDays} days" }

        val currentIv = ivBars.last().iv!!
        val ivMin = ivBars.minOf { it.iv!! }
        val ivMax = ivBars.maxOf { it.iv!! }

        val rank =
            if (ivMax == ivMin) {
                50.0
            } else {
                (currentIv - ivMin) / (ivMax - ivMin) * 100.0
            }

        val now = Instant.now(clock)
        val ivRank = IvRank(rank = rank, currentIv = currentIv, calculatedAt = now)
        cache[symbol] = CachedIvRank(ivRank, now)
        runCatching { store.save(symbol, StoredIvRank(rank = rank, currentIv = currentIv, calculatedAt = now)) }
            .onFailure { logger.warn { "[$symbol] Failed to persist IV rank: ${it.message}" } }
        logger.info {
            "[$symbol] IV Rank: ${"%.1f".format(
                rank,
            )}% (iv=${"%.4f".format(currentIv)}, min=${"%.4f".format(ivMin)}, max=${"%.4f".format(ivMax)})"
        }
        return ivRank
    }
}
