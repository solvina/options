package cz.solvina.options.domain.features.aireview

import cz.solvina.options.domain.features.notification.OpportunityNotificationPort
import cz.solvina.options.domain.features.notification.SpreadOpportunity
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Asks for an AI review of a candidate that just qualified for the day. Fire-and-forget: it never
 * blocks or fails the scan.
 */
fun interface CandidateReviewPort {
    fun requestReview(opportunity: SpreadOpportunity)

    companion object {
        val NoOp = CandidateReviewPort { }
    }
}

/**
 * Creates the review task and owns the candidate email: with a task the email waits for the
 * verdict ([ReviewDispatcher]); without one (review off, cap hit, after the last decision, or the
 * task could not be created) the plain email goes out at once, as before the AI review existed.
 */
@Component
class AiCandidateReviewRequester(
    private val config: AiReviewConfig,
    private val builder: ReviewSnapshotBuilder,
    private val service: AiReviewService,
    private val notifier: OpportunityNotificationPort,
    private val scope: CoroutineScope,
) : CandidateReviewPort {
    override fun requestReview(opportunity: SpreadOpportunity) {
        scope.launch {
            if (!config.enabled) {
                notifier.notify(opportunity)
                return@launch
            }
            val task =
                runCatching { service.createTask(builder.build(opportunity)) }
                    .onFailure { logger.warn(it) { "[${opportunity.symbol}] AI review task not created: ${it.message}" } }
                    .getOrElse {
                        notifier.notify(opportunity) // fail-open: the candidate is still emailed
                        return@launch
                    }
            when {
                task == null -> Unit // already reviewed today; its email belongs to that task
                task.status == AiTaskStatus.SKIPPED -> {
                    notifier.notify(opportunity)
                    service.markEmailed(task, withVerdict = false)
                }
                else -> Unit // the dispatcher emails it with the verdict
            }
        }
    }
}
