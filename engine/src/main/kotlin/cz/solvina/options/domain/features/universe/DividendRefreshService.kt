package cz.solvina.options.domain.features.universe

import cz.solvina.options.domain.features.market.MarketDataPriority
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Refreshes ex-dividend date + amount for the universe's US instruments from [DividendDataPort]
 * (the IBKR IB_DIVIDENDS tick). The tick is corporate-action data delivered regardless of market
 * hours, so this works any day including weekends. Bear-call dividend protection reads what this
 * stores ([InstrumentConfig.exDividendDate] / [InstrumentConfig.nextDividendAmount]).
 */
@Component
class DividendRefreshService(
    private val dividendDataPort: DividendDataPort,
    private val universePort: UniversePort,
) {
    /** Daily pre-market refresh. Paper deployments relaying from prod (dividends.remote) should
     *  schedule this after prod's own refresh, e.g. "0 0 7 * * *". */
    @Scheduled(cron = "\${dividends.refresh-cron:0 30 6 * * *}")
    fun scheduledRefresh() = runBlocking { refresh() }

    /** One-shot a few minutes after startup so a fresh deploy populates without waiting for the cron. */
    @Scheduled(initialDelay = 240_000, fixedDelay = Long.MAX_VALUE)
    fun startupRefresh() = runBlocking { refresh() }

    suspend fun refresh(): Unit =
        withContext(MarketDataPriority.SCANNER) {
            doRefresh()
        }

    private suspend fun doRefresh() {
        val usInstruments =
            universePort
                .getAll()
                .filter { it.enabled }
                .filter { runCatching { universePort.getMarketSchedule(it.symbol).session == "US" }.getOrDefault(false) }
        logger.info { "Dividend refresh: ${usInstruments.size} US instruments" }
        var updated = 0
        for (inst in usInstruments) {
            runCatching {
                val info = dividendDataPort.fetchDividendInfo(inst.symbol)
                if (info != null && (info.exDividendDate != null || info.amount != null)) {
                    universePort.save(inst.copy(exDividendDate = info.exDividendDate, nextDividendAmount = info.amount))
                    updated++
                    logger.info { "[${inst.symbol}] dividend: exDate=${info.exDividendDate} amount=${info.amount}" }
                }
            }.onFailure { e -> logger.warn(e) { "[${inst.symbol}] dividend refresh failed: ${e.message}" } }
        }
        logger.info { "Dividend refresh complete: $updated/${usInstruments.size} updated" }
    }
}
