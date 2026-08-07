package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import cz.solvina.options.domain.features.strategy.live.StockStrategyRunner
import cz.solvina.options.shared.scheduling.runScheduled
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Drives [StockStrategyRunner].
 *
 * Cadence is deliberately just after each European and US open rather than continuous: these are
 * daily-bar strategies, so the only moment a fresh decision exists is once the previous bar has
 * closed, and the entry is a day limit placed just after the opening auction prints. Polling more
 * often would re-evaluate an unchanged bar and risk duplicate entries on a decision that has not
 * moved.
 *
 * ### Two crons, each in its own market's timezone
 * One expression at "09:05 and 15:05 Europe/Berlin" looks like it covers both opens, and does not:
 * 15:05 Berlin is 09:05 in New York, 25 minutes BEFORE the US open, so
 * [cz.solvina.options.domain.features.universe.UniversePort.isMarketOpen] rejected every US symbol
 * and the US half of the universe was never evaluated at all. Anchoring each pass in its own
 * exchange's zone also keeps both correct through the weeks when European and American DST
 * transitions have not yet lined up.
 */
@Component
class StockStrategyScheduler(
    private val runner: StockStrategyRunner,
    private val connectionStatusPort: ConnectionStatusPort,
    private val killSwitch: TradingKillSwitch,
    @Value("\${stock-strategies.run-timeout-minutes:10}") private val runTimeoutMinutes: Long,
) {
    private val runInProgress = AtomicBoolean(false)

    @Scheduled(cron = "\${stock-strategies.eu-cron:0 5 9 * * MON-FRI}", zone = "Europe/Berlin", scheduler = "backgroundTaskScheduler")
    @Scheduled(cron = "\${stock-strategies.us-cron:0 35 9 * * MON-FRI}", zone = "America/New_York", scheduler = "backgroundTaskScheduler")
    fun run() {
        if (killSwitch.scannerPaused) {
            logger.info { "Stock strategy run skipped: paused by kill switch" }
            return
        }
        if (!connectionStatusPort.isConnected()) {
            logger.warn { "Stock strategy run skipped: IBKR not connected" }
            return
        }
        if (!runInProgress.compareAndSet(false, true)) {
            logger.warn { "Stock strategy run skipped: previous run still in progress" }
            return
        }
        try {
            runScheduled(
                name = "Stock strategy",
                timeout = runTimeoutMinutes.minutes,
                expectedPeriod = 6.hours,
            ) {
                runner.runOnce()
            }
        } catch (e: Exception) {
            logger.error(e) { "Stock strategy run failed: ${e.message}" }
        } finally {
            runInProgress.set(false)
        }
    }
}
