package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import cz.solvina.options.domain.features.strategy.live.StockStrategyRunner
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
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

    @Scheduled(cron = "\${stock-strategies.cron:0 5 9,15 * * MON-FRI}", zone = "Europe/Berlin")
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
            runBlocking {
                // Bounded for the same reason as ScannerScheduler: a cron @Scheduled method that
                // never returns is never re-triggered, so one wedged run silently ends the schedule
                // for the rest of the session rather than costing a single pass.
                val completed = withTimeoutOrNull(runTimeoutMinutes.minutes) { runner.runOnce() }
                if (completed == null) {
                    logger.error { "Stock strategy run exceeded ${runTimeoutMinutes}m and was cancelled" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Stock strategy run failed: ${e.message}" }
        } finally {
            runInProgress.set(false)
        }
    }
}
