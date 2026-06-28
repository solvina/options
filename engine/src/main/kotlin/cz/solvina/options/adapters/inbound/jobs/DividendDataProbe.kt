package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.adapters.outbound.ibkr.market.IbkrDividendTickAdapter
import cz.solvina.options.domain.features.universe.UniversePort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * One-shot diagnostic for Phase 3 sub-slice 3b. A few minutes after startup it requests IBKR
 * fundamental data for a couple of US symbols and logs the raw XML (or the error), so we can learn
 * whether the Reuters fundamentals subscription is available on this account and what the
 * ex-dividend XML actually looks like — before building the parser + refresh job against it.
 *
 * Read-only and safe: `reqFundamentalData` places no orders; a missing subscription surfaces as
 * error 430 and is logged. This probe is throwaway — it is replaced by the real refresh job once the
 * format is known.
 */
@Component
class DividendDataProbe(
    private val dividendTickAdapter: IbkrDividendTickAdapter,
    private val universePort: UniversePort,
) {
    @Scheduled(initialDelay = 180_000, fixedDelay = Long.MAX_VALUE)
    fun probe() =
        runBlocking {
            // Probe the IB_DIVIDENDS streaming tick (456): "trailing12m,forward12m,nextDate,nextAmount".
            // This is the no-extra-entitlement path for the forward ex-dividend date + amount.
            val usSymbols =
                universePort
                    .getWatchlist()
                    .filter { runCatching { universePort.getMarketSchedule(it).session == "US" }.getOrDefault(false) }
                    .take(4)
            logger.info { "[DIVIDEND PROBE] probing IB_DIVIDENDS tick(456) for ${usSymbols.map { it.value }}" }
            for (symbol in usSymbols) {
                val tick = dividendTickAdapter.fetchDividendTick(symbol)
                logger.info { "[DIVIDEND PROBE] $symbol IB_DIVIDENDS(456) -> ${tick ?: "NO DATA (error/timeout)"}" }
            }
        }
}
