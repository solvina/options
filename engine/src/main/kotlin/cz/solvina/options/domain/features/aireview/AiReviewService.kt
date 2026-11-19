package cz.solvina.options.domain.features.aireview

import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private val logger = KotlinLogging.logger {}

val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

/**
 * The engine side of the AI review contract: creates tasks, leases them to reviewers, validates
 * and stores results, applies the retry policy, and tracks reviewer liveness. All decisions that
 * matter for trading stay here; a reviewer only claims, reads and answers.
 *
 * Queue times (leases, deadlines, heartbeats) are true wall-clock events, so they use [clock].
 */
@Service
class AiReviewService(
    private val store: AiReviewStorePort,
    private val config: AiReviewConfig,
    private val clock: Clock,
) {
    /**
     * Records a review task for a freshly qualified candidate. Idempotent per (trading date,
     * strategy, symbol). A candidate after the day's last decision, or past the daily cap, is
     * stored as SKIPPED so the gap in coverage stays visible.
     */
    suspend fun createTask(snapshot: ReviewSnapshotV1): AiReviewTask? {
        if (!config.enabled) return null
        val deadline = nextDecision(snapshot.asOf)
        val skipReason =
            when {
                deadline == null -> "AFTER_FINAL_DECISION"
                store.countTasks(snapshot.tradingDate) >= config.dailyTaskCap -> "DAILY_CAP"
                else -> null
            }
        val c = snapshot.candidate
        val task =
            AiReviewTask(
                createdAt = Instant.now(clock),
                tradingDate = snapshot.tradingDate,
                strategyId = enumValueOf(snapshot.strategyId),
                symbol = Symbol(snapshot.symbol),
                expiry = c.expiry,
                shortStrike = c.shortLeg.strike,
                longStrike = c.longLeg.strike,
                deadlineAt = deadline,
                status = if (skipReason == null) AiTaskStatus.OPEN else AiTaskStatus.SKIPPED,
                skipReason = skipReason,
                rulesVersion = snapshot.rulesVersion,
            )
        val reviewers = if (skipReason == null) config.enabledReviewers.map { it.id } else emptyList()
        val inserted = store.insertTask(task, snapshot, reviewers)
        if (!inserted) return null
        logger.info {
            "[${snapshot.symbol}] AI review task ${task.status}${skipReason?.let { " ($it)" } ?: ""} " +
                "${snapshot.strategyId} ${c.shortLeg.strike}/${c.longLeg.strike} exp=${c.expiry} deadline=$deadline " +
                "reviewers=$reviewers"
        }
        return task
    }

    /**
     * The primary reviewer's verdict per symbol for [tradingDate], as a decision at [at] sees it:
     * only results DONE by then count; anything else is "no verdict" (fail-open).
     */
    suspend fun primaryVerdicts(
        tradingDate: LocalDate,
        at: Instant,
    ): Map<Symbol, PrimaryVerdict> {
        if (!config.enabled) return emptyMap()
        val primary = config.primaryReviewer?.id ?: return emptyMap()
        return store
            .findTasksForDate(tradingDate)
            .filter { it.status == AiTaskStatus.OPEN }
            .associate { task ->
                val r =
                    store
                        .findResult(task.id, primary)
                        ?.takeIf { it.status == AiResultStatus.DONE && it.completedAt?.isAfter(at) == false }
                val vetoes = r?.verdict == AiVerdict.VETO && (r.confidence ?: 0.0) >= config.layerMinConfidence
                task.symbol to PrimaryVerdict(task.id, task.strategyId, r?.verdict, r?.confidence, vetoes)
            }
    }

    /**
     * Records on each of [tradingDate]'s tasks what the decision at [at] did with its title. Only
     * the first decision that sees a task counts; a task whose title is missing from [outcomes]
     * once its own decision has passed is DROPPED (stopped qualifying).
     */
    suspend fun recordDecision(
        tradingDate: LocalDate,
        at: Instant,
        outcomes: List<DecisionOutcome>,
    ) {
        if (!config.enabled) return
        val verdicts = primaryVerdicts(tradingDate, at)
        val bySymbol = outcomes.associateBy { it.symbol }
        for (task in store.findTasksForDate(tradingDate)) {
            if (task.status != AiTaskStatus.OPEN || task.decision != null) continue
            val o = bySymbol[task.symbol]
            val decision =
                when {
                    o == null && task.deadlineAt?.isAfter(at) == false -> TaskDecision(at, "DROPPED")
                    o == null -> null
                    o.strategyId != task.strategyId -> TaskDecision(at, "STRATEGY_CHANGED", o.rank, o.score)
                    else ->
                        TaskDecision(
                            at = at,
                            action = o.action,
                            rank = o.rank,
                            score = o.score,
                            expiry = o.expiry,
                            shortStrike = o.shortStrike,
                            longStrike = o.longStrike,
                            midCredit = o.midCredit,
                            shadowSpreadId = o.shadowSpreadId,
                            wouldGate = verdicts[task.symbol]?.vetoes == true,
                        )
                } ?: continue
            store.saveDecision(task.copy(decision = decision))
        }
    }

    /** Reason and rules version per task, for showing next to the would-be trades. */
    suspend fun taskNotes(taskIds: Set<UUID>): Map<UUID, TaskNote> {
        val primary = config.primaryReviewer?.id
        return taskIds
            .mapNotNull { id ->
                val task = store.findTask(id) ?: return@mapNotNull null
                val reason = primary?.let { store.findResult(id, it) }?.takeIf { it.status == AiResultStatus.DONE }
                id to TaskNote(reason?.reason, reason?.verdict, reason?.late == true, task.rulesVersion)
            }.toMap()
    }

    /** Records that [task]'s candidate email went out. */
    suspend fun markEmailed(
        task: AiReviewTask,
        withVerdict: Boolean,
    ) {
        store.saveSurfacing(task.copy(emailedAt = Instant.now(clock), emailHadVerdict = withVerdict))
    }

    /** The first pool decision after [at] on the same NY trading day, or null after the last one. */
    fun nextDecision(at: Instant): Instant? {
        val ny = at.atZone(NEW_YORK)
        return config.decisionTimesNy
            .sorted()
            .map {
                ny
                    .toLocalDate()
                    .atTime(it)
                    .atZone(NEW_YORK)
                    .toInstant()
            }.firstOrNull { it.isAfter(at) }
    }

    suspend fun claim(
        reviewerId: String,
        contractVersions: List<String>,
        snapshotVersions: List<Int>?,
    ): ClaimedTask? {
        if (!config.enabled || config.reviewer(reviewerId) == null) return null
        if (contractVersions.none { it in SUPPORTED_CONTRACT_VERSIONS }) return null
        val versions = snapshotVersions?.toSet()?.takeIf { it.isNotEmpty() } ?: setOf(ReviewSnapshotV1.VERSION)
        val now = Instant.now(clock)
        val claimed =
            store.claimNext(
                reviewerId = reviewerId,
                snapshotVersions = versions,
                now = now,
                leaseUntil = now.plus(Duration.ofMinutes(config.leaseMinutes)),
                leaseToken = UUID.randomUUID().toString(),
                maxAttempts = config.maxAttempts,
            ) ?: return null
        store.logAttempt(AiReviewAttempt(claimed.task.id, reviewerId, claimed.result.attempts, AiAttemptEvent.CLAIMED, now))
        logger.info { "[${claimed.task.symbol}] AI review claimed by $reviewerId (attempt ${claimed.result.attempts})" }
        return claimed
    }

    suspend fun submitResult(
        taskId: UUID,
        submission: ResultSubmission,
    ): SubmitOutcome {
        if (submission.contractVersion !in SUPPORTED_CONTRACT_VERSIONS) {
            return SubmitOutcome.Invalid("contractVersion ${submission.contractVersion} not supported (${SUPPORTED_CONTRACT_VERSIONS})")
        }
        if (!submission.confidence.isFinite() || submission.confidence !in 0.0..1.0) {
            return SubmitOutcome.Invalid("confidence must be within 0..1")
        }
        val task = store.findTask(taskId) ?: return SubmitOutcome.NotFound
        val current = store.findResult(taskId, submission.reviewerId) ?: return SubmitOutcome.NotFound
        if (current.leaseToken != submission.leaseToken) return SubmitOutcome.Conflict
        if (current.status == AiResultStatus.DONE) {
            // Idempotent retry of the same POST; a different answer under the same lease is a conflict.
            return if (current.verdict == submission.verdict && current.reason == submission.reason) {
                SubmitOutcome.Accepted(current.late)
            } else {
                SubmitOutcome.Conflict
            }
        }
        val now = Instant.now(clock)
        val late = task.deadlineAt?.let { now.isAfter(it) } ?: true
        val unregistered = submission.promptHash != null && store.findPrompt(submission.promptHash) == null
        store.saveResult(
            current.copy(
                status = AiResultStatus.DONE,
                contractVersion = submission.contractVersion,
                verdict = submission.verdict,
                confidence = submission.confidence,
                reason = submission.reason,
                risksJson = submission.risksJson,
                newsSummary = submission.newsSummary,
                sourcesJson = submission.sourcesJson,
                provenanceJson = submission.provenanceJson,
                extrasJson = submission.extrasJson,
                rawResponse = submission.rawResponse,
                promptHash = submission.promptHash,
                promptUnregistered = unregistered,
                model = submission.model,
                late = late,
                completedAt = now,
                leaseExpiresAt = null,
            ),
        )
        store.logAttempt(AiReviewAttempt(taskId, submission.reviewerId, current.attempts, AiAttemptEvent.RESULT, now))
        updateStatus(submission.reviewerId) {
            it.copy(lastResultAt = now, consecutiveFailures = 0, model = submission.model ?: it.model)
        }
        logger.info {
            "[${task.symbol}] AI review ${submission.verdict} (${"%.2f".format(submission.confidence)}) by ${submission.reviewerId}" +
                if (late) " — LATE" else ""
        }
        return SubmitOutcome.Accepted(late)
    }

    /** Returns false when the task or the reviewer's row is unknown, or the lease isn't theirs. */
    suspend fun reportFailure(
        taskId: UUID,
        reviewerId: String,
        leaseToken: String,
        kind: AiFailureKind,
        message: String,
    ): Boolean {
        val current = store.findResult(taskId, reviewerId) ?: return false
        if (current.leaseToken != leaseToken) return false
        if (current.status == AiResultStatus.DONE) return true
        val now = Instant.now(clock)
        val attempts = if (kind.consumesAttempt) current.attempts else (current.attempts - 1).coerceAtLeast(0)
        val exhausted = kind.consumesAttempt && attempts >= config.maxAttempts
        store.saveResult(
            current.copy(
                status = if (exhausted) AiResultStatus.FAILED else AiResultStatus.PENDING,
                attempts = attempts,
                leaseExpiresAt = null,
                lastFailureKind = kind,
                lastFailure = message.take(2000),
                completedAt = if (exhausted) now else null,
            ),
        )
        store.logAttempt(AiReviewAttempt(taskId, reviewerId, current.attempts, AiAttemptEvent.FAILURE, now, kind, message.take(2000)))
        if (exhausted) store.logAttempt(AiReviewAttempt(taskId, reviewerId, attempts, AiAttemptEvent.FAILED, now, kind))
        updateStatus(reviewerId) {
            it.copy(
                lastFailure = "$kind: ${message.take(300)}",
                consecutiveFailures = if (kind.consumesAttempt) it.consecutiveFailures + 1 else it.consecutiveFailures,
                state = if (kind == AiFailureKind.AUTH) AiReviewerState.AUTH_FAILED else it.state,
            )
        }
        logger.warn { "AI review failure from $reviewerId on $taskId: $kind ${message.take(200)}${if (exhausted) " — FAILED" else ""}" }
        return true
    }

    suspend fun heartbeat(
        reviewerId: String,
        state: AiReviewerState,
        message: String?,
        provider: String?,
        model: String?,
        reviewerVersion: String?,
    ) {
        val now = Instant.now(clock)
        updateStatus(reviewerId) {
            it.copy(
                state = state,
                message = message?.take(500),
                provider = provider ?: it.provider,
                model = model ?: it.model,
                reviewerVersion = reviewerVersion ?: it.reviewerVersion,
                lastHeartbeatAt = now,
            )
        }
    }

    suspend fun registerPrompt(
        hash: String,
        reviewerId: String,
        promptVersion: String,
        text: String,
    ): PromptOutcome {
        val existing = store.findPrompt(hash)
        if (existing != null) return if (existing == text) PromptOutcome.STORED else PromptOutcome.CONFLICT
        store.savePrompt(hash, reviewerId, promptVersion, text, Instant.now(clock))
        return PromptOutcome.STORED
    }

    suspend fun listReviews(
        from: LocalDate?,
        symbol: String?,
        limit: Int,
    ): List<ReviewWithResults> = store.listReviews(from, symbol?.uppercase(), limit.coerceIn(1, 500))

    suspend fun reviewers(): List<ReviewerView> {
        val now = Instant.now(clock)
        val statuses = store.allReviewerStatuses().associateBy { it.reviewerId }
        return config.reviewers.map { cfg ->
            val status = statuses[cfg.id]
            val silent =
                status?.lastHeartbeatAt == null ||
                    Duration.between(status.lastHeartbeatAt, now).seconds > config.silentAfterSeconds
            ReviewerView(cfg, if (silent) AiReviewerState.SILENT else status!!.state, status)
        }
    }

    /**
     * Housekeeping, run every minute: expired leases go back to PENDING (the attempt stays used —
     * a reviewer that crashes on a task must not loop on it forever), exhausted rows become FAILED,
     * and anything unfinished past its deadline becomes EXPIRED.
     */
    suspend fun sweep() {
        val now = Instant.now(clock)
        store.findExpiredLeases(now).forEach { r ->
            store.saveResult(r.copy(status = AiResultStatus.PENDING, leaseExpiresAt = null))
            store.logAttempt(AiReviewAttempt(r.taskId, r.reviewerId, r.attempts, AiAttemptEvent.LEASE_EXPIRED, now))
        }
        store.findExhausted(config.maxAttempts).forEach { r ->
            store.saveResult(r.copy(status = AiResultStatus.FAILED, completedAt = now))
            store.logAttempt(AiReviewAttempt(r.taskId, r.reviewerId, r.attempts, AiAttemptEvent.FAILED, now, r.lastFailureKind))
        }
        store.findUnfinishedPastDeadline(now).forEach { r ->
            store.saveResult(r.copy(status = AiResultStatus.EXPIRED, leaseExpiresAt = null, completedAt = now))
            store.logAttempt(AiReviewAttempt(r.taskId, r.reviewerId, r.attempts, AiAttemptEvent.EXPIRED, now))
        }
    }

    private suspend fun updateStatus(
        reviewerId: String,
        change: (AiReviewerStatus) -> AiReviewerStatus,
    ) {
        val current = store.findReviewerStatus(reviewerId) ?: AiReviewerStatus(reviewerId, AiReviewerState.READY)
        store.saveReviewerStatus(change(current))
    }
}
