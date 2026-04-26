package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.scanner.ScannerPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class ScannerScheduler(
    private val scannerPort: ScannerPort,
    private val connectionStatusPort: ConnectionStatusPort,
) {
    @Scheduled(cron = "\${scanner.cron:0 */15 10-15 * * MON-FRI}")
    fun runScan() {
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
