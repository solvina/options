package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.scanner.ScannerPort
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@Component
class ScannerScheduler(
    private val scannerPort: ScannerPort,
    private val connectionStatusPort: ConnectionStatusPort,
    private val killSwitch: TradingKillSwitch,
) {
    // Guards against overlapping scans. The scheduler is now a multi-thread pool (so flag/watchdog
    // crons aren't starved), so a slow scan that overruns the 15-min cadence must not run twice
    // concurrently. Also surfaces the overrun as a diagnostic.
    private val scanInProgress = AtomicBoolean(false)

    @Scheduled(cron = "\${scanner.cron:0 */15 9-22 * * MON-FRI}", zone = "Europe/Berlin")
    fun runScan() {
        if (killSwitch.scannerPaused) {
            logger.info { "Scanner skipped: paused by kill switch" }
            return
        }
        if (!connectionStatusPort.isConnected()) {
            logger.warn { "Scanner skipped: IBKR not connected" }
            return
        }
        if (!scanInProgress.compareAndSet(false, true)) {
            logger.warn { "Scanner skipped: previous scan still running (overruns the 15-min cadence)" }
            return
        }
        try {
            logger.info { "Scheduled scanner run triggered" }
            runBlocking {
                runCatching { scannerPort.scan() }
                    .onFailure { e -> logger.error(e) { "Scanner run failed: ${e.message}" } }
            }
        } finally {
            scanInProgress.set(false)
        }
    }
}
