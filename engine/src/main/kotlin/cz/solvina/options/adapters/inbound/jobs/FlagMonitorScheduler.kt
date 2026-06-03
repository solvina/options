package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.flag.FlagManagementService
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfigPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}
private val ET_ZONE = ZoneId.of("America/New_York")
private val BERLIN_ZONE = ZoneId.of("Europe/Berlin")
private const val US_CLOSE_MINUTE = 16 * 60       // 16:00 ET
private const val EU_CLOSE_MINUTE = 17 * 60 + 30  // 17:30 Berlin

@Component
class FlagMonitorScheduler(
    private val flagManagementService: FlagManagementService,
    private val flagTradingConfigPort: FlagTradingConfigPort,
    private val connectionStatusPort: ConnectionStatusPort,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val running = AtomicBoolean(false)

    /**
     * Runs every minute. Triggers EOD liquidation for each market session once we enter the
     * configured window before that session's close.
     */
    @Scheduled(fixedDelay = 60_000)
    fun checkEodLiquidation() {
        if (!connectionStatusPort.isConnected()) {
            logger.debug { "Flag EOD check skipped: IBKR not connected" }
            return
        }
        if (!running.compareAndSet(false, true)) {
            logger.debug { "Flag EOD check skipped: previous run still in progress" }
            return
        }
        scope.launch {
            try {
                runCatching {
                    val config = flagTradingConfigPort.get()
                    val now = ZonedDateTime.now(clock)
                    checkEuClose(now, config)
                    checkUsClose(now, config)
                }.onFailure { e -> logger.error(e) { "Flag EOD liquidation check failed: ${e.message}" } }
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun checkEuClose(now: ZonedDateTime, config: FlagTradingConfig) {
        val eu = now.withZoneSameInstant(BERLIN_ZONE)
        if (eu.dayOfWeek == DayOfWeek.SATURDAY || eu.dayOfWeek == DayOfWeek.SUNDAY) return
        val minuteOfDay = eu.hour * 60 + eu.minute
        if (minuteOfDay > EU_CLOSE_MINUTE) return
        if (minuteOfDay < EU_CLOSE_MINUTE - config.eodLiqMinutesBeforeClose) {
            logger.debug { "Flag EU EOD check skipped: not yet in liquidation window" }
            return
        }
        flagManagementService.checkEodLiquidation("EU")
    }

    private suspend fun checkUsClose(now: ZonedDateTime, config: FlagTradingConfig) {
        val us = now.withZoneSameInstant(ET_ZONE)
        if (us.dayOfWeek == DayOfWeek.SATURDAY || us.dayOfWeek == DayOfWeek.SUNDAY) return
        val minuteOfDay = us.hour * 60 + us.minute
        if (minuteOfDay !in (9 * 60 + 30)..US_CLOSE_MINUTE) return
        if (minuteOfDay < US_CLOSE_MINUTE - config.eodLiqMinutesBeforeClose) {
            logger.debug { "Flag US EOD check skipped: not yet in liquidation window" }
            return
        }
        flagManagementService.checkEodLiquidation("US")
    }
}
