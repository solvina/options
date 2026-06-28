package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.adapters.outbound.ibkr.market.IbkrFundamentalDataAdapter
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
    private val fundamentalDataAdapter: IbkrFundamentalDataAdapter,
    private val universePort: UniversePort,
) {
    @Scheduled(initialDelay = 180_000, fixedDelay = Long.MAX_VALUE)
    fun probe() =
        runBlocking {
            val usSymbols =
                universePort
                    .getWatchlist()
                    .filter { runCatching { universePort.getMarketSchedule(it).session == "US" }.getOrDefault(false) }
                    .take(2)
            logger.info { "[DIVIDEND PROBE] probing ${usSymbols.map { it.value }} for fundamental data" }
            for (symbol in usSymbols) {
                for (reportType in listOf("ReportSnapshot", "ReportsFinSummary", "CalendarReport")) {
                    val xml = fundamentalDataAdapter.fetchFundamentalXml(symbol, reportType)
                    if (xml == null) {
                        logger.info { "[DIVIDEND PROBE] $symbol $reportType -> NO DATA (error/timeout; see warnings above)" }
                    } else {
                        logger.info { "[DIVIDEND PROBE] $symbol $reportType -> len=${xml.length}\n${xml.take(1200)}" }
                    }
                }
            }
        }
}
