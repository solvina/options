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
import kotlinx.coroutines.sync.Mutex
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val logger = KotlinLogging.logger {}

@Component
class SpreadMonitorScheduler(
    private val spreadManagementService: SpreadManagementService,
    private val connectionStatusPort: ConnectionStatusPort,
    private val killSwitch: TradingKillSwitch,
    private val instrumentsConfig: IbkrInstrumentsConfig,
    // Overridable so tests can pin the day of week (the weekday gate below made a wall-clock test
    // fail every weekend). Spring uses the default; no Clock bean is defined.
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Coroutine Mutex (not a thread-bound ReentrantLock): checkExits() suspends and may resume on a
    // different dispatcher thread, so the lock must not be thread-confined — a ReentrantLock.unlock()
    // from a different thread than lock() throws IllegalMonitorStateException, which would leave the
    // lock held forever and silently stop all exit monitoring. Same hazard as documented in
    // PositionReconciliationScheduler.
    private val monitorMutex = Mutex()

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
            if (!monitorMutex.tryLock()) {
                logger.debug { "Spread monitor skipped: previous run still in progress" }
                return@launch
            }

            try {
                runCatching { spreadManagementService.checkExits() }
                    .onFailure { e -> logger.error(e) { "Spread monitor failed: ${e.message}" } }
            } finally {
                monitorMutex.unlock()
            }
        }
    }

    private fun isAnyExchangeOpen(): Boolean =
        instrumentsConfig.exchanges.values.any { hours ->
            val zone = ZoneId.of(hours.timezone)
            val now = ZonedDateTime.now(clock.withZone(zone))
            if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return@any false
            val time = now.toLocalTime()
            !time.isBefore(LocalTime.parse(hours.open)) && time.isBefore(LocalTime.parse(hours.close))
        }
}
