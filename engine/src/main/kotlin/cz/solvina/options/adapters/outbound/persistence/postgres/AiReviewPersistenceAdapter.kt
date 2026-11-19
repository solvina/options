package cz.solvina.options.adapters.outbound.persistence.postgres

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.AiReviewAttemptEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.AiReviewPromptEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.AiReviewResultEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.AiReviewRulesEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.AiReviewTaskEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.AiReviewerStatusEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.AiReviewAttemptRepository
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.AiReviewPromptRepository
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.AiReviewResultRepository
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.AiReviewRulesRepository
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.AiReviewTaskRepository
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.AiReviewerStatusRepository
import cz.solvina.options.domain.features.aireview.AiFailureKind
import cz.solvina.options.domain.features.aireview.AiResultStatus
import cz.solvina.options.domain.features.aireview.AiReviewAttempt
import cz.solvina.options.domain.features.aireview.AiReviewResult
import cz.solvina.options.domain.features.aireview.AiReviewStorePort
import cz.solvina.options.domain.features.aireview.AiReviewTask
import cz.solvina.options.domain.features.aireview.AiReviewerState
import cz.solvina.options.domain.features.aireview.AiReviewerStatus
import cz.solvina.options.domain.features.aireview.AiTaskStatus
import cz.solvina.options.domain.features.aireview.AiVerdict
import cz.solvina.options.domain.features.aireview.ClaimedTask
import cz.solvina.options.domain.features.aireview.ReviewSnapshotV1
import cz.solvina.options.domain.features.aireview.ReviewWithResults
import cz.solvina.options.domain.features.aireview.TaskDecision
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Component
class AiReviewPersistenceAdapter(
    private val tasks: AiReviewTaskRepository,
    private val results: AiReviewResultRepository,
    private val attempts: AiReviewAttemptRepository,
    private val prompts: AiReviewPromptRepository,
    private val rules: AiReviewRulesRepository,
    private val statuses: AiReviewerStatusRepository,
    private val transactions: TransactionTemplate,
    private val objectMapper: ObjectMapper,
) : AiReviewStorePort {
    override suspend fun insertTask(
        task: AiReviewTask,
        snapshot: ReviewSnapshotV1,
        reviewerIds: List<String>,
    ): Boolean =
        io {
            if (tasks.existsByTradingDateAndStrategyIdAndSymbol(task.tradingDate, task.strategyId.name, task.symbol.value)) {
                return@io false
            }
            try {
                transactions.execute {
                    tasks.saveAndFlush(task.toEntity(objectMapper.writeValueAsString(snapshot)))
                    reviewerIds.forEach { results.save(AiReviewResult(taskId = task.id, reviewerId = it).toEntity()) }
                }
                true
            } catch (e: DataIntegrityViolationException) {
                false // a concurrent insert of the same title/day won the unique key
            }
        }

    override suspend fun countTasks(tradingDate: LocalDate): Long = io { tasks.countByTradingDate(tradingDate) }

    override suspend fun claimNext(
        reviewerId: String,
        snapshotVersions: Set<Int>,
        now: Instant,
        leaseUntil: Instant,
        leaseToken: String,
        maxAttempts: Int,
    ): ClaimedTask? =
        io {
            transactions.execute {
                val row = results.lockNextClaimable(reviewerId, snapshotVersions, now, maxAttempts) ?: return@execute null
                row.status = AiResultStatus.IN_PROGRESS.name
                row.attempts += 1
                row.leaseToken = leaseToken
                row.leaseExpiresAt = leaseUntil
                row.claimedAt = now
                val saved = results.save(row)
                val task = tasks.findById(saved.taskId).orElseThrow()
                ClaimedTask(task.toDomain(), saved.toDomain(), task.snapshot)
            }
        }

    override suspend fun findTask(taskId: UUID): AiReviewTask? = io { tasks.findById(taskId).orElse(null)?.toDomain() }

    override suspend fun findResult(
        taskId: UUID,
        reviewerId: String,
    ): AiReviewResult? = io { results.findByTaskIdAndReviewerId(taskId, reviewerId)?.toDomain() }

    override suspend fun findResults(taskId: UUID): List<AiReviewResult> =
        io { results.findByTaskIdIn(listOf(taskId)).map { it.toDomain() }.sortedBy { it.reviewerId } }

    override suspend fun findSnapshot(taskId: UUID): ReviewSnapshotV1? =
        io {
            tasks.findById(taskId).orElse(null)?.let { t ->
                runCatching { objectMapper.readValue(t.snapshot, ReviewSnapshotV1::class.java) }
                    .onFailure { logger.warn { "AI review task $taskId: snapshot unreadable: ${it.message}" } }
                    .getOrNull()
            }
        }

    override suspend fun findTasksToSurface(since: Instant): List<AiReviewTask> = io { tasks.findToSurface(since).map { it.toDomain() } }

    override suspend fun saveSurfacing(task: AiReviewTask) {
        io {
            tasks.findById(task.id).ifPresent { e ->
                e.emailedAt = task.emailedAt
                e.emailHadVerdict = task.emailHadVerdict
                e.followUpSentAt = task.followUpSentAt
                e.telegramSentAt = task.telegramSentAt
                tasks.save(e)
            }
        }
    }

    override suspend fun findTasksForDate(tradingDate: LocalDate): List<AiReviewTask> =
        io { tasks.findByTradingDate(tradingDate).map { it.toDomain() } }

    override suspend fun saveDecision(task: AiReviewTask) {
        val d = task.decision ?: return
        io {
            tasks.findById(task.id).ifPresent { e ->
                e.decisionAt = d.at
                e.decisionAction = d.action
                e.decisionRank = d.rank
                e.decisionScore = d.score?.let { BigDecimal(it).setScale(4, RoundingMode.HALF_UP) }
                e.decisionExpiry = d.expiry
                e.decisionShortStrike = d.shortStrike
                e.decisionLongStrike = d.longStrike
                e.decisionMidCredit = d.midCredit
                e.shadowSpreadId = d.shadowSpreadId
                e.wouldGate = d.wouldGate
                tasks.save(e)
            }
        }
    }

    override suspend fun findReviewsSince(since: Instant): List<ReviewWithResults> =
        io {
            val found = tasks.findByCreatedAtGreaterThanEqualOrderByCreatedAt(since)
            val byTask = results.findByTaskIdIn(found.map { it.id }).groupBy { it.taskId }
            found.map { t -> ReviewWithResults(t.toDomain(), byTask[t.id].orEmpty().map { it.toDomain() }) }
        }

    override suspend fun saveResult(result: AiReviewResult): AiReviewResult = io { results.save(result.toEntity()).toDomain() }

    override suspend fun logAttempt(attempt: AiReviewAttempt) {
        io {
            attempts.save(
                AiReviewAttemptEntity(
                    taskId = attempt.taskId,
                    reviewerId = attempt.reviewerId,
                    attempt = attempt.attempt,
                    event = attempt.event.name,
                    failureKind = attempt.failureKind?.name,
                    message = attempt.message,
                    at = attempt.at,
                ),
            )
        }
    }

    override suspend fun findExpiredLeases(now: Instant): List<AiReviewResult> =
        io { results.findByStatusAndLeaseExpiresAtBefore(AiResultStatus.IN_PROGRESS.name, now).map { it.toDomain() } }

    override suspend fun findUnfinishedPastDeadline(now: Instant): List<AiReviewResult> =
        io { results.findUnfinishedPastDeadline(now).map { it.toDomain() } }

    override suspend fun findExhausted(maxAttempts: Int): List<AiReviewResult> =
        io { results.findByStatusAndAttemptsGreaterThanEqual(AiResultStatus.PENDING.name, maxAttempts).map { it.toDomain() } }

    override suspend fun listReviews(
        from: LocalDate?,
        symbol: String?,
        limit: Int,
    ): List<ReviewWithResults> =
        io {
            val page = PageRequest.of(0, limit)
            val found =
                when {
                    from != null && symbol != null ->
                        tasks.findBySymbolAndTradingDateGreaterThanEqualOrderByCreatedAtDesc(symbol, from, page)
                    from != null -> tasks.findByTradingDateGreaterThanEqualOrderByCreatedAtDesc(from, page)
                    symbol != null -> tasks.findBySymbolOrderByCreatedAtDesc(symbol, page)
                    else -> tasks.findAllByOrderByCreatedAtDesc(page)
                }
            val byTask = results.findByTaskIdIn(found.map { it.id }).groupBy { it.taskId }
            found.map { t -> ReviewWithResults(t.toDomain(), byTask[t.id].orEmpty().map { it.toDomain() }.sortedBy { it.reviewerId }) }
        }

    override suspend fun findPrompt(hash: String): String? = io { prompts.findById(hash).orElse(null)?.text }

    override suspend fun savePrompt(
        hash: String,
        reviewerId: String,
        promptVersion: String,
        text: String,
        at: Instant,
    ) {
        io { prompts.save(AiReviewPromptEntity(hash, reviewerId, promptVersion, text, at)) }
    }

    override suspend fun registerRules(
        rules: Map<String, Any?>,
        at: Instant,
    ): String =
        io {
            val canonical = objectMapper.writeValueAsString(sortKeys(objectMapper.valueToTree(rules)))
            val version =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(canonical.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                    .take(RULES_HASH_LENGTH)
            if (!this.rules.existsById(version)) {
                runCatching { this.rules.save(AiReviewRulesEntity(version, canonical, at)) }
            }
            version
        }

    override suspend fun findReviewerStatus(reviewerId: String): AiReviewerStatus? =
        io { statuses.findById(reviewerId).orElse(null)?.toDomain() }

    override suspend fun saveReviewerStatus(status: AiReviewerStatus) {
        io { statuses.save(status.toEntity()) }
    }

    override suspend fun allReviewerStatuses(): List<AiReviewerStatus> = io { statuses.findAll().map { it.toDomain() } }

    // ---------------------------------------------------------------------------------------------

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /** Same content → same JSON → same hash, whatever order the maps were built in. */
    private fun sortKeys(node: JsonNode): JsonNode =
        when {
            node.isObject -> {
                val sorted = objectMapper.createObjectNode()
                node
                    .fieldNames()
                    .asSequence()
                    .sorted()
                    .forEach { sorted.set<JsonNode>(it, sortKeys(node.get(it))) }
                sorted
            }
            node.isArray -> objectMapper.createArrayNode().also { arr -> node.forEach { arr.add(sortKeys(it)) } }
            else -> node
        }

    private fun AiReviewTask.toEntity(snapshotJson: String) =
        AiReviewTaskEntity(
            id = id,
            createdAt = createdAt,
            tradingDate = tradingDate,
            strategyId = strategyId.name,
            symbol = symbol.value,
            expiry = expiry,
            shortStrike = shortStrike,
            longStrike = longStrike,
            deadlineAt = deadlineAt,
            status = status.name,
            skipReason = skipReason,
            snapshotVersion = snapshotVersion,
            snapshot = snapshotJson,
            rulesVersion = rulesVersion,
            emailedAt = emailedAt,
            emailHadVerdict = emailHadVerdict,
            followUpSentAt = followUpSentAt,
            telegramSentAt = telegramSentAt,
        )

    private fun AiReviewTaskEntity.toDomain() =
        AiReviewTask(
            id = id,
            createdAt = createdAt,
            tradingDate = tradingDate,
            strategyId = StrategyId.valueOf(strategyId),
            symbol = Symbol(symbol),
            expiry = expiry,
            shortStrike = shortStrike,
            longStrike = longStrike,
            deadlineAt = deadlineAt,
            status = AiTaskStatus.valueOf(status),
            skipReason = skipReason,
            snapshotVersion = snapshotVersion,
            rulesVersion = rulesVersion,
            emailedAt = emailedAt,
            emailHadVerdict = emailHadVerdict,
            followUpSentAt = followUpSentAt,
            telegramSentAt = telegramSentAt,
            decision =
                decisionAt?.let {
                    TaskDecision(
                        at = it,
                        action = decisionAction ?: "UNKNOWN",
                        rank = decisionRank,
                        score = decisionScore?.toDouble(),
                        expiry = decisionExpiry,
                        shortStrike = decisionShortStrike,
                        longStrike = decisionLongStrike,
                        midCredit = decisionMidCredit,
                        shadowSpreadId = shadowSpreadId,
                        wouldGate = wouldGate == true,
                    )
                },
        )

    private fun AiReviewResult.toEntity() =
        AiReviewResultEntity(
            id = id,
            taskId = taskId,
            reviewerId = reviewerId,
            status = status.name,
            attempts = attempts,
            leaseToken = leaseToken,
            leaseExpiresAt = leaseExpiresAt,
            claimedAt = claimedAt,
            contractVersion = contractVersion,
            verdict = verdict?.name,
            confidence = confidence?.let { BigDecimal(it).setScale(4, RoundingMode.HALF_UP) },
            reason = reason,
            risks = risksJson,
            newsSummary = newsSummary,
            sources = sourcesJson,
            provenance = provenanceJson,
            extras = extrasJson,
            rawResponse = rawResponse,
            promptHash = promptHash,
            promptUnregistered = promptUnregistered,
            model = model,
            late = late,
            completedAt = completedAt,
            lastFailureKind = lastFailureKind?.name,
            lastFailure = lastFailure,
        )

    private fun AiReviewResultEntity.toDomain() =
        AiReviewResult(
            id = id,
            taskId = taskId,
            reviewerId = reviewerId,
            status = AiResultStatus.valueOf(status),
            attempts = attempts,
            leaseToken = leaseToken,
            leaseExpiresAt = leaseExpiresAt,
            claimedAt = claimedAt,
            contractVersion = contractVersion,
            verdict = verdict?.let { AiVerdict.valueOf(it) },
            confidence = confidence?.toDouble(),
            reason = reason,
            risksJson = risks,
            newsSummary = newsSummary,
            sourcesJson = sources,
            provenanceJson = provenance,
            extrasJson = extras,
            rawResponse = rawResponse,
            promptHash = promptHash,
            promptUnregistered = promptUnregistered,
            model = model,
            late = late,
            completedAt = completedAt,
            lastFailureKind = lastFailureKind?.let { AiFailureKind.valueOf(it) },
            lastFailure = lastFailure,
        )

    private fun AiReviewerStatus.toEntity() =
        AiReviewerStatusEntity(
            reviewerId = reviewerId,
            state = state.name,
            message = message,
            provider = provider,
            model = model,
            reviewerVersion = reviewerVersion,
            lastHeartbeatAt = lastHeartbeatAt,
            lastResultAt = lastResultAt,
            lastFailure = lastFailure,
            consecutiveFailures = consecutiveFailures,
        )

    private fun AiReviewerStatusEntity.toDomain() =
        AiReviewerStatus(
            reviewerId = reviewerId,
            state = AiReviewerState.valueOf(state),
            message = message,
            provider = provider,
            model = model,
            reviewerVersion = reviewerVersion,
            lastHeartbeatAt = lastHeartbeatAt,
            lastResultAt = lastResultAt,
            lastFailure = lastFailure,
            consecutiveFailures = consecutiveFailures,
        )

    private companion object {
        const val RULES_HASH_LENGTH = 16
    }
}
