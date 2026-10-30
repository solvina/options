package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.shadow.ShadowSpreadService
import cz.solvina.options.shared.scheduling.runScheduled
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Daily revaluation of would-be spreads, 30 minutes before each market's close and anchored in that
 * market's own timezone (the European and US DST weeks don't line up). Only shadows whose market is
 * open at that moment are revalued, so each pass touches its own half of the book.
 */
@Component
class ShadowSpreadScheduler(
    private val service: ShadowSpreadService,
    private val connectionStatusPort: ConnectionStatusPort,
) {
    private val running = AtomicBoolean(false)

    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Europe/Berlin", scheduler = "backgroundTaskScheduler")
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "America/New_York", scheduler = "backgroundTaskScheduler")
    fun run() {
        if (!connectionStatusPort.isConnected()) {
            logger.warn { "Shadow spread revaluation skipped: IBKR not connected" }
            return
        }
        if (!running.compareAndSet(false, true)) return
        try {
            runScheduled(name = "Shadow spreads", timeout = 15.minutes, expectedPeriod = 12.hours) {
                val n = service.revalueOpenMarkets()
                logger.info { "Shadow spread revaluation: $n open position(s) checked" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Shadow spread revaluation failed: ${e.message}" }
        } finally {
            running.set(false)
        }
    }
}
