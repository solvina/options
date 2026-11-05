package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.scanner.ScannerPoolConfig
import cz.solvina.options.domain.features.scanner.ScannerService
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import cz.solvina.options.shared.scheduling.runScheduled
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Decides the day's candidate pool, in New York time so it survives DST changes: the main decision
 * three hours before the close (19:00 in Prague most of the year) and a top-up an hour before it.
 * On Nov 3–5 the first decision time saw 9 of the 10 titles that fit a $20k account.
 */
@Component
class PoolDecisionScheduler(
    private val scanner: ScannerService,
    private val poolConfig: ScannerPoolConfig,
    private val connectionStatusPort: ConnectionStatusPort,
    private val killSwitch: TradingKillSwitch,
) {
    @Scheduled(cron = "0 0 13 * * MON-FRI", zone = "America/New_York", scheduler = "backgroundTaskScheduler")
    fun mainDecision() = decide(finalOfDay = false)

    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "America/New_York", scheduler = "backgroundTaskScheduler")
    fun topUp() = decide(finalOfDay = true)

    private fun decide(finalOfDay: Boolean) {
        if (!poolConfig.enabled) return
        if (killSwitch.scannerPaused || !connectionStatusPort.isConnected()) {
            logger.warn { "Pool decision skipped: scanner paused or IBKR not connected" }
            return
        }
        runScheduled(name = "Pool decision", timeout = 20.minutes, expectedPeriod = 24.hours) {
            scanner.decidePool(finalOfDay)
        }
    }
}
