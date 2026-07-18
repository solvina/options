package cz.solvina.options.domain.features.universe

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Refreshes [InstrumentConfig.nextEarningsDate] for the universe's US instruments from
 * [EarningsDataPort]. Mirrors [DividendRefreshService]: external corporate-schedule data, so it
 * works any day including weekends. The spread selectors read what this stores and reject entries
 * whose expiry spans the earnings date.
 *
 * A failed fetch keeps the previously stored date (better a stale future date that blocks an entry
 * than silently dropping the gate); dates already in the past are ignored by the selectors, so
 * stale history is harmless.
 */
@Component
class EarningsRefreshService(
    private val earningsDataPort: EarningsDataPort,
    private val universePort: UniversePort,
) {
    /** Daily pre-market refresh, after the dividend refresh. */
    @Scheduled(cron = "\${earnings.refresh-cron:0 45 6 * * *}")
    fun scheduledRefresh() = runBlocking { refresh() }

    /** One-shot after startup so a fresh deploy populates without waiting for the cron. */
    @Scheduled(initialDelay = 300_000, fixedDelay = Long.MAX_VALUE)
    fun startupRefresh() = runBlocking { refresh() }

    suspend fun refresh() {
        val usInstruments =
            universePort
                .getAll()
                .filter { it.enabled }
                .filter { runCatching { universePort.getMarketSchedule(it.symbol).session == "US" }.getOrDefault(false) }
        logger.info { "Earnings refresh: ${usInstruments.size} US instruments" }
        var updated = 0
        for (inst in usInstruments) {
            runCatching {
                val date = earningsDataPort.fetchNextEarningsDate(inst.symbol)
                if (date != null && date != inst.nextEarningsDate) {
                    universePort.save(inst.copy(nextEarningsDate = date))
                    updated++
                    logger.info { "[${inst.symbol}] earnings: next report $date" }
                }
            }.onFailure { e -> logger.warn { "[${inst.symbol}] earnings refresh failed: ${e.message}" } }
            // Unauthenticated public API — pace requests so a 112-symbol sweep stays polite.
            delay(400)
        }
        logger.info { "Earnings refresh complete: $updated/${usInstruments.size} updated" }
    }
}
