package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import cz.solvina.options.domain.features.spread.SpreadManagementService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class SpreadMonitorScheduler(
    private val spreadManagementService: SpreadManagementService,
    private val connectionStatusPort: ConnectionStatusPort,
    private val killSwitch: TradingKillSwitch,
) {
    @Scheduled(fixedDelayString = "\${scanner.monitor-delay-ms:60000}")
    fun monitorSpreads() {
        if (killSwitch.monitorPaused) {
            logger.debug { "Spread monitor skipped: paused by kill switch" }
            return
        }
        if (!connectionStatusPort.isConnected()) {
            logger.debug { "Spread monitor skipped: IBKR not connected" }
            return
        }
        runBlocking {
            runCatching { spreadManagementService.checkExits() }
                .onFailure { e -> logger.error(e) { "Spread monitor failed: ${e.message}" } }
        }
    }
}
