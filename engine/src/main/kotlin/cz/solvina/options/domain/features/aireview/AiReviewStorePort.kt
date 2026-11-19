package cz.solvina.options.domain.features.aireview

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Persistence for AI review tasks and results. Rows are never deleted (retrospective record).
 */
interface AiReviewStorePort {
    /**
     * Inserts [task] with its [snapshot] and one PENDING result per id in [reviewerIds].
     * Returns false when a task for the same (tradingDate, strategyId, symbol) already exists.
     */
    suspend fun insertTask(
        task: AiReviewTask,
        snapshot: ReviewSnapshotV1,
        reviewerIds: List<String>,
    ): Boolean

    suspend fun countTasks(tradingDate: LocalDate): Long

    /**
     * Atomically picks the open task with the earliest deadline whose result for [reviewerId] is
     * claimable (PENDING, or IN_PROGRESS with an expired lease), with attempts below
     * [maxAttempts] and the deadline still ahead, and leases it: IN_PROGRESS, attempts + 1,
     * [leaseToken] until [leaseUntil]. Null when nothing is waiting.
     */
    suspend fun claimNext(
        reviewerId: String,
        snapshotVersions: Set<Int>,
        now: Instant,
        leaseUntil: Instant,
        leaseToken: String,
        maxAttempts: Int,
    ): ClaimedTask?

    suspend fun findTask(taskId: UUID): AiReviewTask?

    suspend fun findResult(
        taskId: UUID,
        reviewerId: String,
    ): AiReviewResult?

    suspend fun saveResult(result: AiReviewResult): AiReviewResult

    suspend fun logAttempt(attempt: AiReviewAttempt)

    /** Results IN_PROGRESS whose lease expired before [now]. */
    suspend fun findExpiredLeases(now: Instant): List<AiReviewResult>

    /** PENDING / IN_PROGRESS results of tasks whose deadline passed before [now]. */
    suspend fun findUnfinishedPastDeadline(now: Instant): List<AiReviewResult>

    /** PENDING results that have used up their attempts. */
    suspend fun findExhausted(maxAttempts: Int): List<AiReviewResult>

    /** Newest first. */
    suspend fun listReviews(
        from: LocalDate?,
        symbol: String?,
        limit: Int,
    ): List<ReviewWithResults>

    /** Stored prompt text for [hash], or null. */
    suspend fun findPrompt(hash: String): String?

    suspend fun savePrompt(
        hash: String,
        reviewerId: String,
        promptVersion: String,
        text: String,
        at: Instant,
    )

    /** Stores the rule configuration (if new) and returns its version hash. */
    suspend fun registerRules(
        rules: Map<String, Any?>,
        at: Instant,
    ): String

    suspend fun findReviewerStatus(reviewerId: String): AiReviewerStatus?

    suspend fun saveReviewerStatus(status: AiReviewerStatus)

    suspend fun allReviewerStatuses(): List<AiReviewerStatus>
}
