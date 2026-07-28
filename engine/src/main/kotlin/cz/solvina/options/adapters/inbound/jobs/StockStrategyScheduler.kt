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
 * closed, and the plan's entry is a day limit placed just after the opening auction prints
 * (~09:05 Berlin). Polling more often would re-evaluate an unchanged bar and risk duplicate entries
 * on a decision that has not moved.
 */
@Component
class StockStrategyScheduler(
    private val runner: StockStrategyRunner,
    private val connectionStatusPort: ConnectionStatusPort,
    private val killSwitch: TradingKillSwitch,
    @Value("\${stock-strategies.run-timeout-minutes:10}") private val runTimeoutMinutes: Long,
) {
    private val runInProgress = AtomicBoolean(false)

    @Scheduled(cron = "\${stock-strategies.cron:0 5 9,15 * * MON-FRI}", zone = "Europe/Berlin", scheduler = "backgroundTaskScheduler")
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
