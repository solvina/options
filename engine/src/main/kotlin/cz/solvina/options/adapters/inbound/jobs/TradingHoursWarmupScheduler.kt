package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.spread.SpreadCloserRegistry
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Keeps the [cz.solvina.options.adapters.outbound.ibkr.TradingHoursCache] warm so the synchronous
 * `isMarketOpen` check has today's real holiday/half-day schedule before any trading decision. Opening
 * a spread already fetches its underlying contract details (which populates the calendar opportunistically);
 * this job guarantees coverage for held names that aren't being scanned — e.g. a stuck-CLOSING spread on a
 * market holiday, where trading into a closed exchange would otherwise be attempted.
 *
 * Runs shortly after startup and periodically thereafter (liquid hours change only daily, so a coarse
 * cadence suffices). Skipped while IBKR is disconnected.
 */
@Component
class TradingHoursWarmupScheduler(
    private val universePort: UniversePort,
    private val spreadClosers: SpreadCloserRegistry,
    private val contractCache: IbkrContractCache,
    private val connectionStatusPort: ConnectionStatusPort,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    @Scheduled(
        fixedDelayString = "\${trading-hours.refresh-ms:21600000}",
        initialDelayString = "\${trading-hours.initial-delay-ms:45000}",
    )
    fun refresh() {
        if (!connectionStatusPort.isConnected()) {
            logger.debug { "Trading-hours warmup skipped: IBKR not connected" }
            return
        }
        scope.launch {
            if (!mutex.tryLock()) {
                logger.debug { "Trading-hours warmup skipped: previous run still in progress" }
                return@launch
            }
            try {
                runCatching { warm() }
                    .onFailure { e -> logger.warn(e) { "Trading-hours warmup failed: ${e.message}" } }
            } finally {
                mutex.unlock()
            }
        }
    }

    private suspend fun warm() {
        val held = (spreadClosers.allOpen() + spreadClosers.allClosing()).map { it.symbol }
        val symbols: List<Symbol> = (universePort.getWatchlist() + held).distinct()
        if (symbols.isEmpty()) return
        logger.info { "Trading-hours warmup: refreshing calendar for ${symbols.size} symbol(s)" }
        for (symbol in symbols) {
            contractCache.warmTradingHours(symbol)
        }
    }
}
