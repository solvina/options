package cz.solvina.options.domain.features.aireview

import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Result contract versions this engine accepts. */
val SUPPORTED_CONTRACT_VERSIONS = setOf("1")

enum class AiTaskStatus { OPEN, SKIPPED }

enum class AiResultStatus { PENDING, IN_PROGRESS, DONE, FAILED, EXPIRED }

enum class AiVerdict { APPROVE, VETO, ABSTAIN }

enum class AiFailureKind {
    AUTH,
    RATE_LIMIT,
    TIMEOUT,
    PROVIDER_ERROR,
    INVALID_OUTPUT,
    OTHER,
    ;

    /** AUTH and RATE_LIMIT say nothing about the task, so they never use up one of its attempts. */
    val consumesAttempt: Boolean get() = this != AUTH && this != RATE_LIMIT
}

enum class AiReviewerState { READY, BUSY, PAUSED, AUTH_FAILED, DEGRADED, SILENT }

enum class AiAttemptEvent { CLAIMED, RESULT, FAILURE, LEASE_EXPIRED, EXPIRED, FAILED }

/**
 * One title + strategy on one NY trading day — what a verdict is about. The strikes are the ones
 * reviewed; the pool decision may later enter different ones. Unique per (tradingDate, strategyId,
 * symbol).
 */
data class AiReviewTask(
    val id: UUID = UUID.randomUUID(),
    val createdAt: Instant,
    val tradingDate: LocalDate,
    val strategyId: StrategyId,
    val symbol: Symbol,
    val expiry: LocalDate,
    val shortStrike: BigDecimal,
    val longStrike: BigDecimal,
    /** The next pool decision this review can still influence; null for a SKIPPED task. */
    val deadlineAt: Instant?,
    val status: AiTaskStatus,
    val skipReason: String? = null,
    val snapshotVersion: Int = ReviewSnapshotV1.VERSION,
    val rulesVersion: String? = null,
    /** Surfacing (D5): when the candidate email went out, with or without a verdict. */
    val emailedAt: Instant? = null,
    /** False when the email went out "AI review unavailable"; a later verdict is a follow-up. */
    val emailHadVerdict: Boolean? = null,
    val followUpSentAt: Instant? = null,
    val telegramSentAt: Instant? = null,
    /** D6: what the first pool decision after the review did with the title. */
    val decision: TaskDecision? = null,
)

/**
 * What a pool decision did with a reviewed title (AI review D6). Set once, by the first decision
 * that saw it. [action]: RECORDED (a new would-be trade), ALREADY_HELD (the title already had an
 * open would-be trade, so nothing new could be scored), STRATEGY_CHANGED (it re-qualified as the
 * other strategy, so the verdict doesn't apply), DROPPED (stopped qualifying by its decision).
 */
data class TaskDecision(
    val at: Instant,
    val action: String,
    val rank: Int? = null,
    val score: Double? = null,
    val expiry: LocalDate? = null,
    val shortStrike: BigDecimal? = null,
    val longStrike: BigDecimal? = null,
    val midCredit: BigDecimal? = null,
    val shadowSpreadId: UUID? = null,
    /** The primary verdict was an on-time VETO at or above the layer's confidence: gating would have blocked it. */
    val wouldGate: Boolean = false,
)

/** The primary reviewer's verdict on a title, as a decision at a given moment sees it. */
data class PrimaryVerdict(
    val taskId: UUID,
    val strategyId: StrategyId,
    /** Null when the review wasn't DONE before the decision (fail-open: treated as approve). */
    val verdict: AiVerdict?,
    val confidence: Double?,
    /** VETO with confidence ≥ [AiReviewConfig.layerMinConfidence]. */
    val vetoes: Boolean,
)

/** The primary verdict's reason for a task (any time, also late), with the task's rules version. */
data class TaskNote(
    val reason: String?,
    val verdict: AiVerdict?,
    val late: Boolean,
    val rulesVersion: String?,
)

/** One ranked title at a decision, for [AiReviewService.recordDecision]. */
data class DecisionOutcome(
    val strategyId: StrategyId,
    val symbol: Symbol,
    val action: String,
    val rank: Int,
    val score: Double,
    val expiry: LocalDate,
    val shortStrike: BigDecimal,
    val longStrike: BigDecimal,
    val midCredit: BigDecimal,
    val shadowSpreadId: UUID?,
)

/**
 * One reviewer's work on one task. The engine reads only [verdict] and [confidence]; the JSON
 * fields are what the reviewer sent, stored as-is and shown, never interpreted.
 */
data class AiReviewResult(
    val id: UUID = UUID.randomUUID(),
    val taskId: UUID,
    val reviewerId: String,
    val status: AiResultStatus = AiResultStatus.PENDING,
    val attempts: Int = 0,
    val leaseToken: String? = null,
    val leaseExpiresAt: Instant? = null,
    val claimedAt: Instant? = null,
    val contractVersion: String? = null,
    val verdict: AiVerdict? = null,
    val confidence: Double? = null,
    val reason: String? = null,
    val risksJson: String? = null,
    val newsSummary: String? = null,
    val sourcesJson: String? = null,
    val provenanceJson: String? = null,
    val extrasJson: String? = null,
    val rawResponse: String? = null,
    val promptHash: String? = null,
    /** True when [promptHash] was not registered when the result arrived. */
    val promptUnregistered: Boolean = false,
    val model: String? = null,
    val late: Boolean = false,
    val completedAt: Instant? = null,
    val lastFailureKind: AiFailureKind? = null,
    val lastFailure: String? = null,
)

/** A task handed to a reviewer: the task, its leased result row and the stored snapshot JSON. */
data class ClaimedTask(
    val task: AiReviewTask,
    val result: AiReviewResult,
    val snapshotJson: String,
)

/** What a reviewer submits; JSON parts are already serialized by the inbound adapter. */
data class ResultSubmission(
    val reviewerId: String,
    val leaseToken: String,
    val contractVersion: String,
    val verdict: AiVerdict,
    val confidence: Double,
    val reason: String,
    val risksJson: String? = null,
    val newsSummary: String? = null,
    val sourcesJson: String? = null,
    val provenanceJson: String? = null,
    val extrasJson: String? = null,
    val rawResponse: String? = null,
    val promptHash: String? = null,
    val model: String? = null,
)

sealed interface SubmitOutcome {
    data class Accepted(
        val late: Boolean,
    ) : SubmitOutcome

    data object NotFound : SubmitOutcome

    data object Conflict : SubmitOutcome

    data class Invalid(
        val message: String,
    ) : SubmitOutcome
}

enum class PromptOutcome { STORED, CONFLICT }

data class AiReviewerStatus(
    val reviewerId: String,
    val state: AiReviewerState,
    val message: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val reviewerVersion: String? = null,
    val lastHeartbeatAt: Instant? = null,
    val lastResultAt: Instant? = null,
    val lastFailure: String? = null,
    val consecutiveFailures: Int = 0,
)

/** A configured reviewer with its live status, for the dashboard and health check. */
data class ReviewerView(
    val config: ReviewerConfig,
    val state: AiReviewerState,
    val status: AiReviewerStatus?,
)

data class ReviewWithResults(
    val task: AiReviewTask,
    val results: List<AiReviewResult>,
)

/** Append-only history of what happened to a (task, reviewer) pair. */
data class AiReviewAttempt(
    val taskId: UUID,
    val reviewerId: String,
    val attempt: Int,
    val event: AiAttemptEvent,
    val at: Instant,
    val failureKind: AiFailureKind? = null,
    val message: String? = null,
)
