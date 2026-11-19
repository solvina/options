package cz.solvina.options.domain.features.aireview

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.LocalTime

/**
 * `ai-review.*` — AI review of spread candidates (engine/docs/AI_CANDIDATE_REVIEW_PLAN.md).
 *
 * The engine owns the queue; reviewers (Claude, OpenAI, anything) are HTTP clients of the AI
 * Review API. Off by default: with [enabled] false no task is ever created and the API hands out
 * nothing.
 */
@ConfigurationProperties("ai-review")
data class AiReviewConfig(
    val enabled: Boolean = false,
    /** Bearer token the reviewer endpoints require. Blank = every reviewer call is refused. */
    val apiToken: String = "",
    val reviewers: List<ReviewerConfig> = emptyList(),
    /** How long a claimed task belongs to the reviewer that claimed it. */
    val leaseMinutes: Long = 15,
    /** Attempts per (task, reviewer) before it is FAILED. AUTH / RATE_LIMIT failures don't count. */
    val maxAttempts: Int = 3,
    /** Runaway guard, not a budget: tasks created per NY trading day; later titles are SKIPPED. */
    val dailyTaskCap: Int = 40,
    /**
     * Pool decision times, New York. Must match PoolDecisionScheduler (13:00 main, 15:00 top-up).
     * A task's deadline is the next of these; a title first qualifying after the last one gets no
     * review because no decision will ever use it.
     */
    val decisionTimesNy: List<LocalTime> = listOf(LocalTime.of(13, 0), LocalTime.of(15, 0)),
    /** A reviewer with no heartbeat for this long is reported SILENT. */
    val silentAfterSeconds: Long = 180,
    /**
     * How long the candidate email waits for the primary verdict before going out "AI review
     * unavailable" (never past the task's decision). A verdict arriving later is a follow-up.
     */
    val emailWaitMinutes: Long = 15,
    /** Forum-topic thread for verdict messages (same bot and chat as engine alerts). Blank = none. */
    val telegramMessageThreadId: String = "",
    /** D6 AI layer: a VETO removes a title from `ai_selected` only at or above this confidence (0 = any veto). */
    val layerMinConfidence: Double = 0.0,
) {
    val primaryReviewer: ReviewerConfig? get() = enabledReviewers.firstOrNull { it.primary }

    fun reviewer(id: String): ReviewerConfig? = reviewers.firstOrNull { it.id == id && it.enabled }

    val enabledReviewers: List<ReviewerConfig> get() = reviewers.filter { it.enabled }
}

data class ReviewerConfig(
    /** Stable reviewer id, e.g. `claude-opus`. Results are grouped and scored by it. */
    val id: String,
    val enabled: Boolean = true,
    /** The primary reviewer's verdict goes into the email and is the only one that may ever gate. */
    val primary: Boolean = false,
    /** Send the portfolio section (account size, open positions) to this reviewer. */
    val includePortfolio: Boolean = true,
)
