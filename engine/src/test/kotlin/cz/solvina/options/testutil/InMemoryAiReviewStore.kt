package cz.solvina.options.testutil

import cz.solvina.options.domain.features.aireview.AiResultStatus
import cz.solvina.options.domain.features.aireview.AiReviewAttempt
import cz.solvina.options.domain.features.aireview.AiReviewResult
import cz.solvina.options.domain.features.aireview.AiReviewStorePort
import cz.solvina.options.domain.features.aireview.AiReviewTask
import cz.solvina.options.domain.features.aireview.AiReviewerStatus
import cz.solvina.options.domain.features.aireview.AiTaskStatus
import cz.solvina.options.domain.features.aireview.ClaimedTask
import cz.solvina.options.domain.features.aireview.ReviewSnapshotV1
import cz.solvina.options.domain.features.aireview.ReviewWithResults
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** In-memory [AiReviewStorePort] mirroring the Postgres adapter's semantics (incl. the claim query). */
class InMemoryAiReviewStore : AiReviewStorePort {
    val tasks = linkedMapOf<UUID, AiReviewTask>()
    val snapshots = mutableMapOf<UUID, ReviewSnapshotV1>()
    val results = mutableMapOf<Pair<UUID, String>, AiReviewResult>()
    val attempts = mutableListOf<AiReviewAttempt>()
    val prompts = mutableMapOf<String, String>()
    val rules = mutableMapOf<String, Map<String, Any?>>()
    val statuses = mutableMapOf<String, AiReviewerStatus>()

    override suspend fun insertTask(
        task: AiReviewTask,
        snapshot: ReviewSnapshotV1,
        reviewerIds: List<String>,
    ): Boolean {
        val duplicate =
            tasks.values.any { it.tradingDate == task.tradingDate && it.strategyId == task.strategyId && it.symbol == task.symbol }
        if (duplicate) return false
        tasks[task.id] = task
        snapshots[task.id] = snapshot
        reviewerIds.forEach { results[task.id to it] = AiReviewResult(taskId = task.id, reviewerId = it) }
        return true
    }

    override suspend fun countTasks(tradingDate: LocalDate): Long = tasks.values.count { it.tradingDate == tradingDate }.toLong()

    override suspend fun claimNext(
        reviewerId: String,
        snapshotVersions: Set<Int>,
        now: Instant,
        leaseUntil: Instant,
        leaseToken: String,
        maxAttempts: Int,
    ): ClaimedTask? {
        val candidate =
            results.values
                .filter { it.reviewerId == reviewerId && it.attempts < maxAttempts }
                .filter {
                    it.status == AiResultStatus.PENDING ||
                        (it.status == AiResultStatus.IN_PROGRESS && it.leaseExpiresAt?.isBefore(now) == true)
                }.mapNotNull { r -> tasks[r.taskId]?.let { r to it } }
                .filter { (_, t) ->
                    t.status == AiTaskStatus.OPEN && t.snapshotVersion in snapshotVersions && t.deadlineAt?.isAfter(now) == true
                }.minWithOrNull(compareBy({ it.second.deadlineAt }, { it.second.createdAt }))
                ?: return null
        val (row, task) = candidate
        val leased =
            row.copy(
                status = AiResultStatus.IN_PROGRESS,
                attempts = row.attempts + 1,
                leaseToken = leaseToken,
                leaseExpiresAt = leaseUntil,
                claimedAt = now,
            )
        results[task.id to reviewerId] = leased
        return ClaimedTask(task, leased, "{\"symbol\":\"${task.symbol.value}\",\"portfolio\":{}}")
    }

    override suspend fun findTask(taskId: UUID): AiReviewTask? = tasks[taskId]

    override suspend fun findResult(
        taskId: UUID,
        reviewerId: String,
    ): AiReviewResult? = results[taskId to reviewerId]

    override suspend fun findResults(taskId: UUID): List<AiReviewResult> =
        results.values.filter { it.taskId == taskId }.sortedBy { it.reviewerId }

    override suspend fun findSnapshot(taskId: UUID): ReviewSnapshotV1? = snapshots[taskId]

    override suspend fun findTasksToSurface(since: Instant): List<AiReviewTask> =
        tasks.values.filter {
            it.status == AiTaskStatus.OPEN &&
                !it.createdAt.isBefore(since) &&
                (it.emailedAt == null || it.telegramSentAt == null || (it.emailHadVerdict == false && it.followUpSentAt == null))
        }

    override suspend fun findTasksForDate(tradingDate: LocalDate) = tasks.values.filter { it.tradingDate == tradingDate }

    override suspend fun saveDecision(task: AiReviewTask) {
        tasks[task.id] = tasks.getValue(task.id).copy(decision = task.decision)
    }

    override suspend fun findReviewsSince(since: Instant) =
        tasks.values
            .filter { !it.createdAt.isBefore(since) }
            .sortedBy { it.createdAt }
            .map { t -> ReviewWithResults(t, results.filterKeys { it.first == t.id }.values.toList()) }

    override suspend fun saveSurfacing(task: AiReviewTask) {
        tasks[task.id] =
            tasks.getValue(task.id).copy(
                emailedAt = task.emailedAt,
                emailHadVerdict = task.emailHadVerdict,
                followUpSentAt = task.followUpSentAt,
                telegramSentAt = task.telegramSentAt,
            )
    }

    override suspend fun saveResult(result: AiReviewResult): AiReviewResult {
        results[result.taskId to result.reviewerId] = result
        return result
    }

    override suspend fun logAttempt(attempt: AiReviewAttempt) {
        attempts += attempt
    }

    override suspend fun findExpiredLeases(now: Instant): List<AiReviewResult> =
        results.values.filter { it.status == AiResultStatus.IN_PROGRESS && it.leaseExpiresAt?.isBefore(now) == true }

    override suspend fun findUnfinishedPastDeadline(now: Instant): List<AiReviewResult> =
        results.values.filter {
            (it.status == AiResultStatus.PENDING || it.status == AiResultStatus.IN_PROGRESS) &&
                tasks[it.taskId]?.deadlineAt?.isBefore(now) == true
        }

    override suspend fun findExhausted(maxAttempts: Int): List<AiReviewResult> =
        results.values.filter { it.status == AiResultStatus.PENDING && it.attempts >= maxAttempts }

    override suspend fun listReviews(
        from: LocalDate?,
        symbol: String?,
        limit: Int,
    ): List<ReviewWithResults> =
        tasks.values
            .filter { from == null || !it.tradingDate.isBefore(from) }
            .filter { symbol == null || it.symbol.value == symbol }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .map { t -> ReviewWithResults(t, results.values.filter { it.taskId == t.id }) }

    override suspend fun findPrompt(hash: String): String? = prompts[hash]

    override suspend fun savePrompt(
        hash: String,
        reviewerId: String,
        promptVersion: String,
        text: String,
        at: Instant,
    ) {
        prompts[hash] = text
    }

    override suspend fun registerRules(
        rules: Map<String, Any?>,
        at: Instant,
    ): String {
        val version = rules.hashCode().toString(16)
        this.rules[version] = rules
        return version
    }

    override suspend fun findReviewerStatus(reviewerId: String): AiReviewerStatus? = statuses[reviewerId]

    override suspend fun saveReviewerStatus(status: AiReviewerStatus) {
        statuses[status.reviewerId] = status
    }

    override suspend fun allReviewerStatuses(): List<AiReviewerStatus> = statuses.values.toList()
}
