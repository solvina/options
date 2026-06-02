package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.flag.FlagManagementService
import cz.solvina.options.domain.features.flag.config.FlagTradingConfigPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

private val logger = KotlinLogging.logger {}
private val ET_FLAG = ZoneId.of("America/New_York")
private const val US_CLOSE_MINUTE = 16 * 60  // 16:00 ET

@Component
class FlagMonitorScheduler(
    private val flagManagementService: FlagManagementService,
    private val flagTradingConfigPort: FlagTradingConfigPort,
    private val connectionStatusPort: ConnectionStatusPort,
) {
    /**
     * Runs every minute during US market hours.
     * Triggers EOD liquidation once we enter the configured liquidation window before close.
     */
    @Scheduled(fixedDelay = 60_000)
    fun checkEodLiquidation() {
        if (!connectionStatusPort.isConnected()) {
            logger.debug { "Flag EOD check skipped: IBKR not connected" }
            return
        }
        if (!isUsTradingDay()) {
            return
        }
        val now = ZonedDateTime.now(ET_FLAG)
        val minuteOfDay = now.hour * 60 + now.minute
        // Only run during trading hours (9:30–16:00 ET)
        if (minuteOfDay !in (9 * 60 + 30)..US_CLOSE_MINUTE) return

        runBlocking {
            runCatching {
                val config = flagTradingConfigPort.get()
                if (minuteOfDay < US_CLOSE_MINUTE - config.eodLiqMinutesBeforeClose) {
                    logger.debug { "Flag EOD check skipped: not yet in liquidation window" }
                    return@runCatching
                }
                flagManagementService.checkEodLiquidation()
            }.onFailure { e -> logger.error(e) { "Flag EOD liquidation check failed: ${e.message}" } }
        }
    }

    private fun isUsTradingDay(): Boolean {
        val dow = ZonedDateTime.now(ET_FLAG).dayOfWeek
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
    }
}
