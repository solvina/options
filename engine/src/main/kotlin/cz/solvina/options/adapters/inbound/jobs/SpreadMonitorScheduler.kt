package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.adapters.outbound.ibkr.IbkrInstrumentsConfig
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import cz.solvina.options.domain.features.spread.SpreadManagementService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.locks.ReentrantLock

private val logger = KotlinLogging.logger {}

@Component
class SpreadMonitorScheduler(
    private val spreadManagementService: SpreadManagementService,
    private val connectionStatusPort: ConnectionStatusPort,
    private val killSwitch: TradingKillSwitch,
    private val instrumentsConfig: IbkrInstrumentsConfig,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val monitorLock = ReentrantLock()

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
        if (!isAnyExchangeOpen()) {
            logger.debug { "Spread monitor skipped: no exchange currently open" }
            return
        }

        scope.launch {
            if (!monitorLock.tryLock()) {
                logger.debug { "Spread monitor skipped: previous run still in progress" }
                return@launch
            }

            try {
                runCatching { spreadManagementService.checkExits() }
                    .onFailure { e -> logger.error(e) { "Spread monitor failed: ${e.message}" } }
            } finally {
                monitorLock.unlock()
            }
        }
    }

    private fun isAnyExchangeOpen(): Boolean =
        instrumentsConfig.exchanges.values.any { hours ->
            val zone = ZoneId.of(hours.timezone)
            val now = ZonedDateTime.now(zone)
            if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return@any false
            val time = now.toLocalTime()
            !time.isBefore(LocalTime.parse(hours.open)) && time.isBefore(LocalTime.parse(hours.close))
        }
}
