package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import cz.solvina.options.domain.features.spread.SpreadManagementService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

private val logger = KotlinLogging.logger {}
private val ET = ZoneId.of("America/New_York")

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
        if (!isMarketHours()) {
            logger.debug { "Spread monitor skipped: outside market hours" }
            return
        }
        runBlocking {
            runCatching { spreadManagementService.checkExits() }
                .onFailure { e -> logger.error(e) { "Spread monitor failed: ${e.message}" } }
        }
    }

    private fun isMarketHours(): Boolean {
        val now = ZonedDateTime.now(ET)
        val dow = now.dayOfWeek
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false
        val hour = now.hour
        val minute = now.minute
        val minuteOfDay = hour * 60 + minute
        return minuteOfDay in (9 * 60 + 30)..(16 * 60)
    }
}
