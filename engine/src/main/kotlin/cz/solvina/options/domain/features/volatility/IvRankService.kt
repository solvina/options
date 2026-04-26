package cz.solvina.options.domain.features.volatility

import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.models.IvRank
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class IvRankService(
    private val histDataPort: HistoricalDataPort,
    private val config: ScannerConfig,
) : VolatilityPort {
    private data class CachedIvRank(
        val ivRank: IvRank,
        val cachedAt: Instant,
    )

    private val cache = ConcurrentHashMap<Symbol, CachedIvRank>()

    override suspend fun getIvRank(symbol: Symbol): IvRank {
        val cached = cache[symbol]
        val ttl = Duration.ofMinutes(config.ivCacheTtlMinutes)
        if (cached != null && Instant.now().isBefore(cached.cachedAt.plus(ttl))) {
            logger.debug { "[$symbol] IV Rank cache hit: ${cached.ivRank.rank}" }
            return cached.ivRank
        }

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

        val ivRank = IvRank(rank = rank, calculatedAt = Instant.now())
        cache[symbol] = CachedIvRank(ivRank, Instant.now())
        logger.info {
            "[$symbol] IV Rank: ${"%.1f".format(
                rank,
            )}% (iv=${"%.4f".format(currentIv)}, min=${"%.4f".format(ivMin)}, max=${"%.4f".format(ivMax)})"
        }
        return ivRank
    }
}
