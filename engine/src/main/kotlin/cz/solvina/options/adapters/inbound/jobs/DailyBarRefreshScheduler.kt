package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.bars.DailyBarRefreshService
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.shared.scheduling.runScheduled
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Drives [DailyBarRefreshService] once a night.
 *
 * 23:30 Berlin is after both the 17:30 Xetra close and the 22:00 US close, so one pass covers every
 * market, and it is hours ahead of the 09:05 first strategy run — a pass that is slowed by IBKR
 * pacing or a few timeouts still finishes long before anything reads the bars it wrote.
 */
@Component
class DailyBarRefreshScheduler(
    private val refresh: DailyBarRefreshService,
    private val connectionStatusPort: ConnectionStatusPort,
    @Value("\${stock-strategies.bar-refresh.enabled:true}") private val enabled: Boolean,
    @Value("\${stock-strategies.bar-refresh.timeout-minutes:60}") private val timeoutMinutes: Long,
) {
    private val runInProgress = AtomicBoolean(false)

    @Scheduled(
        cron = "\${stock-strategies.bar-refresh.cron:0 30 23 * * MON-FRI}",
        zone = "Europe/Berlin",
        scheduler = "backgroundTaskScheduler",
    )
    fun run() {
        if (!enabled) {
            logger.debug { "Daily bar refresh skipped: disabled" }
            return
        }
        if (!connectionStatusPort.isConnected()) {
            logger.warn { "Daily bar refresh skipped: IBKR not connected — bars will be a day staler" }
            return
        }
        if (!runInProgress.compareAndSet(false, true)) {
            logger.warn { "Daily bar refresh skipped: previous run still in progress" }
            return
        }
        try {
            runScheduled(
                name = "Daily bar refresh",
                // Generous: the pass is paced at ~10s/symbol by design, so a full universe of
                // assignments legitimately takes ~20 minutes and must not be killed mid-way.
                timeout = timeoutMinutes.minutes,
                expectedPeriod = 24.hours,
            ) {
                refresh.refreshOnce()
            }
        } catch (e: Exception) {
            logger.error(e) { "Daily bar refresh failed: ${e.message}" }
        } finally {
            runInProgress.set(false)
        }
    }
}
