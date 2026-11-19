package cz.solvina.options.domain.features.aireview

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

@Component
class AiCandidateReviewRequester(
    private val config: AiReviewConfig,
    private val builder: ReviewSnapshotBuilder,
    private val service: AiReviewService,
    private val scope: CoroutineScope,
) : CandidateReviewPort {
    override fun requestReview(opportunity: SpreadOpportunity) {
        if (!config.enabled) return
        scope.launch {
            runCatching { service.createTask(builder.build(opportunity)) }
                .onFailure { logger.warn(it) { "[${opportunity.symbol}] AI review task not created: ${it.message}" } }
        }
    }
}
