package cz.solvina.options.adapters.inbound.api

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import cz.solvina.options.aireview.api.AiReviewApi
import cz.solvina.options.aireview.dto.ClaimRequest
import cz.solvina.options.aireview.dto.Heartbeat
import cz.solvina.options.aireview.dto.PromptRegistration
import cz.solvina.options.aireview.dto.ReportCalibrationBucket
import cz.solvina.options.aireview.dto.ReportCohort
import cz.solvina.options.aireview.dto.ReportDifference
import cz.solvina.options.aireview.dto.ReportRulesVersion
import cz.solvina.options.aireview.dto.ResultAck
import cz.solvina.options.aireview.dto.ReviewFailure
import cz.solvina.options.aireview.dto.ReviewSummary
import cz.solvina.options.aireview.dto.ReviewSummaryResultsInner
import cz.solvina.options.aireview.dto.ReviewTask
import cz.solvina.options.aireview.dto.Risk
import cz.solvina.options.domain.features.aireview.AiFailureKind
import cz.solvina.options.domain.features.aireview.AiReviewConfig
import cz.solvina.options.domain.features.aireview.AiReviewReportService
import cz.solvina.options.domain.features.aireview.AiReviewService
import cz.solvina.options.domain.features.aireview.AiReviewerState
import cz.solvina.options.domain.features.aireview.AiVerdict
import cz.solvina.options.domain.features.aireview.Cohort
import cz.solvina.options.domain.features.aireview.Difference
import cz.solvina.options.domain.features.aireview.PromptOutcome
import cz.solvina.options.domain.features.aireview.ResultSubmission
import cz.solvina.options.domain.features.aireview.SubmitOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import cz.solvina.options.aireview.dto.AiReviewReport as AiReviewReportDto
import cz.solvina.options.aireview.dto.ReviewResult as ReviewResultDto
import cz.solvina.options.aireview.dto.ReviewerStatus as ReviewerStatusDto

/**
 * The AI Review API (openapi/cz.solvina.options.aireview.yaml). Thin: maps DTOs to the domain
 * and back; queue, retry and validation rules live in [AiReviewService]. Reviewer routes are
 * token-protected by [AiReviewTokenFilter].
 */
@RestController
@RequestMapping
class AiReviewApiImpl(
    private val service: AiReviewService,
    private val config: AiReviewConfig,
    private val objectMapper: ObjectMapper,
    private val reports: AiReviewReportService,
) : AiReviewApi {
    override suspend fun getAiReviewReport(
        from: LocalDate?,
        rulesVersion: String?,
    ): ResponseEntity<AiReviewReportDto> {
        val r = reports.report(from, rulesVersion)
        return ResponseEntity.ok(
            AiReviewReportDto(
                reviewerId = r.reviewerId,
                generatedAt = r.generatedAt.atOffset(ZoneOffset.UTC),
                from = r.from,
                rulesVersion = r.rulesVersion,
                cohorts = r.cohorts.map { it.toDto() },
                rulesOnly = r.rulesOnly.toDto(),
                withAi = r.withAi.toDto(),
                removedByAi = r.removedByAi.toDto(),
                addedByAi = r.addedByAi.toDto(),
                lossesAvoidedMinusWinsForgone = r.lossesAvoidedMinusWinsForgone.toBigDecimal(),
                approveMinusVeto = r.approveMinusVeto?.toDto(),
                calibration =
                    r.calibration.map { ReportCalibrationBucket(it.low.toBigDecimal(), it.high.toBigDecimal(), it.closed, it.right) },
                rulesVersions = r.rulesVersions.map { ReportRulesVersion(it.version, it.firstSeenAt.atOffset(ZoneOffset.UTC), it.tasks) },
                decisions = r.decisions,
                wouldGate = r.wouldGate,
                candidates = r.candidates.map { it.toDto() },
                candidateApproveMinusVeto = r.candidateApproveMinusVeto?.toDto(),
                candidateLossesAvoidedMinusWinsForgone = r.candidateLossesAvoidedMinusWinsForgone.toBigDecimal(),
            ),
        )
    }

    private fun Difference.toDto() =
        ReportDifference(mean.toBigDecimal(), approveClosed, vetoClosed, low?.toBigDecimal(), high?.toBigDecimal())

    private fun Cohort.toDto() =
        ReportCohort(
            label = label,
            count = count,
            closed = closed,
            wins = wins,
            realizedPnl = realizedPnl.toBigDecimal(),
            open = open,
            markedPnl = markedPnl.toBigDecimal(),
            tradingDays = tradingDays,
            winRate = winRate?.toBigDecimal(),
            avgPnl = avgPnl?.toBigDecimal(),
        )

    override suspend fun claimAiReviewTask(claimRequest: ClaimRequest): ResponseEntity<ReviewTask> {
        val claimed =
            service.claim(claimRequest.reviewerId, claimRequest.contractVersions, claimRequest.snapshotVersions)
                ?: return ResponseEntity.noContent().build()
        val snapshot: MutableMap<String, Any> = objectMapper.readValue(claimed.snapshotJson, SNAPSHOT_TYPE)
        if (config.reviewer(claimRequest.reviewerId)?.includePortfolio != true) snapshot.remove("portfolio")
        val task = claimed.task
        return ResponseEntity.ok(
            ReviewTask(
                taskId = task.id,
                leaseToken = claimed.result.leaseToken!!,
                leaseExpiresAt = claimed.result.leaseExpiresAt!!.atOffset(ZoneOffset.UTC),
                deadlineAt = task.deadlineAt!!.atOffset(ZoneOffset.UTC),
                tradingDate = task.tradingDate,
                strategyId = ReviewTask.StrategyId.forValue(task.strategyId.name),
                symbol = task.symbol.value,
                snapshotVersion = task.snapshotVersion,
                snapshot = snapshot,
                attempt = claimed.result.attempts,
            ),
        )
    }

    override suspend fun submitAiReviewResult(
        taskId: UUID,
        reviewResult: ReviewResultDto,
    ): ResponseEntity<ResultAck> {
        val r = reviewResult
        val submission =
            ResultSubmission(
                reviewerId = r.reviewerId,
                leaseToken = r.leaseToken,
                contractVersion = r.contractVersion.value,
                verdict = AiVerdict.valueOf(r.verdict.value),
                confidence = r.confidence.toDouble(),
                reason = r.reason,
                risksJson = r.risks?.let { risks -> json(risks.map { it.normalized() }) },
                newsSummary = r.newsSummary,
                sourcesJson = r.sources?.let { json(it) },
                provenanceJson = r.provenance?.let { json(it) },
                extrasJson = r.extras?.let { json(it) },
                rawResponse = r.rawResponse,
                promptHash = r.provenance?.promptHash,
                model = r.provenance?.model,
            )
        return when (val outcome = service.submitResult(taskId, submission)) {
            is SubmitOutcome.Accepted -> ResponseEntity.ok(ResultAck(late = outcome.late))
            SubmitOutcome.NotFound -> ResponseEntity.notFound().build()
            SubmitOutcome.Conflict -> throw ResponseStatusException(HttpStatus.CONFLICT, "lease or existing result does not match")
            is SubmitOutcome.Invalid -> throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, outcome.message)
        }
    }

    override suspend fun reportAiReviewFailure(
        taskId: UUID,
        reviewFailure: ReviewFailure,
    ): ResponseEntity<Unit> {
        val found =
            service.reportFailure(
                taskId = taskId,
                reviewerId = reviewFailure.reviewerId,
                leaseToken = reviewFailure.leaseToken,
                kind = AiFailureKind.valueOf(reviewFailure.kind.value),
                message = reviewFailure.message,
            )
        return if (found) ResponseEntity.ok().build() else ResponseEntity.notFound().build()
    }

    override suspend fun aiReviewerHeartbeat(
        reviewerId: String,
        heartbeat: Heartbeat,
    ): ResponseEntity<Unit> {
        service.heartbeat(
            reviewerId = reviewerId,
            state = AiReviewerState.valueOf(heartbeat.state.value),
            message = heartbeat.message,
            provider = heartbeat.provider,
            model = heartbeat.model,
            reviewerVersion = heartbeat.reviewerVersion,
        )
        return ResponseEntity.ok().build()
    }

    override suspend fun registerAiReviewPrompt(
        promptHash: String,
        promptRegistration: PromptRegistration,
    ): ResponseEntity<Unit> =
        when (
            service.registerPrompt(promptHash, promptRegistration.reviewerId, promptRegistration.promptVersion, promptRegistration.text)
        ) {
            PromptOutcome.STORED -> ResponseEntity.ok().build()
            PromptOutcome.CONFLICT -> throw ResponseStatusException(HttpStatus.CONFLICT, "hash already registered with different text")
        }

    override fun listAiReviews(
        from: LocalDate?,
        symbol: String?,
        limit: Int,
    ): ResponseEntity<Flow<ReviewSummary>> =
        ResponseEntity.ok(
            flow {
                service.listReviews(from, symbol, limit).forEach { (task, results) ->
                    emit(
                        ReviewSummary(
                            taskId = task.id,
                            tradingDate = task.tradingDate,
                            strategyId = task.strategyId.name,
                            symbol = task.symbol.value,
                            expiry = task.expiry,
                            shortStrike = task.shortStrike,
                            longStrike = task.longStrike,
                            createdAt = task.createdAt.atOffset(ZoneOffset.UTC),
                            deadlineAt = task.deadlineAt?.atOffset(ZoneOffset.UTC),
                            results =
                                results.map { r ->
                                    ReviewSummaryResultsInner(
                                        reviewerId = r.reviewerId,
                                        status = ReviewSummaryResultsInner.Status.forValue(r.status.name),
                                        verdict = r.verdict?.let { ReviewSummaryResultsInner.Verdict.forValue(it.name) },
                                        confidence = r.confidence?.let { BigDecimal.valueOf(it) },
                                        reason = r.reason,
                                        late = r.late,
                                        completedAt = r.completedAt?.atOffset(ZoneOffset.UTC),
                                        model = r.model,
                                    )
                                },
                        ),
                    )
                }
            },
        )

    override fun listAiReviewers(): ResponseEntity<Flow<ReviewerStatusDto>> =
        ResponseEntity.ok(
            flow {
                service.reviewers().forEach { v ->
                    emit(
                        ReviewerStatusDto(
                            reviewerId = v.config.id,
                            enabled = v.config.enabled,
                            primary = v.config.primary,
                            gating = false,
                            state = ReviewerStatusDto.State.forValue(v.state.name),
                            lastHeartbeatAt = v.status?.lastHeartbeatAt?.atOffset(ZoneOffset.UTC),
                            lastResultAt = v.status?.lastResultAt?.atOffset(ZoneOffset.UTC),
                            lastFailure = v.status?.lastFailure,
                            model = v.status?.model,
                        ),
                    )
                }
            },
        )

    private fun json(value: Any): String = objectMapper.writeValueAsString(value)

    /** Unknown risk categories are kept as OTHER rather than failing the whole result. */
    private fun Risk.normalized(): Map<String, String> =
        mapOf(
            "category" to
                category
                    .uppercase()
                    .takeIf { it in RISK_CATEGORIES }
                    .orEmpty()
                    .ifEmpty { "OTHER" },
            "severity" to severity.value,
            "description" to description,
        )

    private companion object {
        val SNAPSHOT_TYPE = object : TypeReference<MutableMap<String, Any>>() {}
        val RISK_CATEGORIES =
            setOf("EARNINGS", "NEWS", "EVENT", "MACRO", "SECTOR", "TECHNICAL", "VOLATILITY", "LIQUIDITY", "SETUP", "OTHER")
    }
}
