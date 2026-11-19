package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.aireview.AiReviewConfig
import cz.solvina.options.domain.features.aireview.AiReviewService
import cz.solvina.options.shared.scheduling.runScheduled
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.minutes

/** Every minute: expired leases back to PENDING, exhausted rows FAILED, past-deadline rows EXPIRED. */
@Component
class AiReviewSweepScheduler(
    private val service: AiReviewService,
    private val config: AiReviewConfig,
) {
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000, scheduler = "backgroundTaskScheduler")
    fun sweep() {
        if (!config.enabled) return
        runScheduled(name = "AI review sweep", timeout = 2.minutes) { service.sweep() }
    }
}
