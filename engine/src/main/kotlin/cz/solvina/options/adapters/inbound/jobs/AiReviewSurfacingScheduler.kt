package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.aireview.AiReviewConfig
import cz.solvina.options.domain.features.aireview.AiReviewWeeklyDigest
import cz.solvina.options.domain.features.aireview.AiReviewerWatchdog
import cz.solvina.options.domain.features.aireview.ReviewDispatcher
import cz.solvina.options.shared.scheduling.runScheduled
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.minutes

/** AI review D5/D5b/D6: emails + Telegram verdicts every 30 s, reviewer liveness every minute, Friday digest. */
@Component
class AiReviewSurfacingScheduler(
    private val dispatcher: ReviewDispatcher,
    private val watchdog: AiReviewerWatchdog,
    private val digest: AiReviewWeeklyDigest,
    private val config: AiReviewConfig,
) {
    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000, scheduler = "backgroundTaskScheduler")
    fun dispatch() {
        if (!config.enabled) return
        runScheduled(name = "AI review dispatch", timeout = 3.minutes) { dispatcher.dispatch() }
    }

    // First check two minutes after start, so a reviewer that heartbeated through an engine
    // restart is not reported silent.
    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000, scheduler = "backgroundTaskScheduler")
    fun watch() {
        if (!config.enabled) return
        runScheduled(name = "AI reviewer watchdog", timeout = 2.minutes) { watchdog.check() }
    }

    @Scheduled(cron = "0 30 16 * * FRI", zone = "America/New_York", scheduler = "backgroundTaskScheduler")
    fun weekly() {
        if (!config.enabled) return
        runScheduled(name = "AI review weekly digest", timeout = 2.minutes) { digest.send() }
    }
}
