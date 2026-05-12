package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.scanner.ScannerPort
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class ScannerScheduler(
    private val scannerPort: ScannerPort,
    private val connectionStatusPort: ConnectionStatusPort,
    private val killSwitch: TradingKillSwitch,
) {
    @Scheduled(cron = "\${scanner.cron:0 */15 10-15 * * MON-FRI}", zone = "\${app.timezone:}")
    fun runScan() {
        if (killSwitch.scannerPaused) {
            logger.info { "Scanner skipped: paused by kill switch" }
            return
        }
        if (!connectionStatusPort.isConnected()) {
            logger.warn { "Scanner skipped: IBKR not connected" }
            return
        }
        logger.info { "Scheduled scanner run triggered" }
        runBlocking {
            runCatching { scannerPort.scan() }
                .onFailure { e -> logger.error(e) { "Scanner run failed: ${e.message}" } }
        }
    }
}
