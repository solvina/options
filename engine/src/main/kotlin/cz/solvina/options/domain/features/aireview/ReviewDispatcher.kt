package cz.solvina.options.domain.features.aireview

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.notification.OpportunityNotificationPort
import cz.solvina.options.domain.features.notification.ReviewNote
import cz.solvina.options.domain.features.notification.ReviewRisk
import cz.solvina.options.domain.features.notification.ReviewSource
import cz.solvina.options.domain.features.notification.SpreadOpportunity
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionGreeks
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Surfaces reviews (AI review D5). Every pass, for each open task of the last two days:
 * - **email**: once the primary reviewer is DONE, the candidate email goes out with its verdict.
 *   Fail-open: when the primary FAILED or EXPIRED, or [AiReviewConfig.emailWaitMinutes] (capped
 *   at the task's decision) have passed, it goes out "AI review unavailable"; a verdict arriving
 *   later becomes a short follow-up in the same thread.
 * - **Telegram**: one message per verdict in the "AI reviews" topic.
 *
 * The email is rendered from the stored snapshot, so an engine restart loses nothing.
 */
@Service
class ReviewDispatcher(
    private val store: AiReviewStorePort,
    private val config: AiReviewConfig,
    private val notifier: OpportunityNotificationPort,
    private val telegram: AiReviewNotificationPort,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    suspend fun dispatch() {
        if (!config.enabled) return
        val now = Instant.now(clock)
        for (task in store.findTasksToSurface(now.minus(LOOKBACK))) {
            runCatching { surface(task, now) }
                .onFailure { logger.warn(it) { "[${task.symbol}] AI review surfacing failed: ${it.message}" } }
        }
    }

    private suspend fun surface(
        task: AiReviewTask,
        now: Instant,
    ) {
        val results = store.findResults(task.id)
        val primaryId = config.primaryReviewer?.id
        val primary = results.firstOrNull { it.reviewerId == primaryId }
        val done = primary?.takeIf { it.status == AiResultStatus.DONE }
        var updated = task

        if (task.emailedAt == null) {
            val unavailable = unavailableReason(task, primaryId, primary, now)
            if (done != null || unavailable != null) {
                val opportunity = opportunity(task) ?: return
                val note = done?.let { note(it, results) } ?: ReviewNote(primaryId ?: "-", null, unavailableReason = unavailable)
                notifier.notifyReviewed(opportunity, note.copy(otherReviewers = others(results, primaryId)), threadKey(task))
                updated = updated.copy(emailedAt = now, emailHadVerdict = done != null)
            }
        } else if (task.emailHadVerdict == false && task.followUpSentAt == null && done != null) {
            val opportunity = opportunity(task) ?: return
            notifier.notifyReviewFollowUp(opportunity, note(done, results), threadKey(task))
            updated = updated.copy(followUpSentAt = now)
        }

        if (task.telegramSentAt == null && done != null) {
            telegram.announce(telegramText(task, done))
            updated = updated.copy(telegramSentAt = now)
        }
        if (updated != task) store.saveSurfacing(updated)
    }

    /** Why the email should go out without a verdict now, or null to keep waiting. */
    private fun unavailableReason(
        task: AiReviewTask,
        primaryId: String?,
        primary: AiReviewResult?,
        now: Instant,
    ): String? {
        val waitUntil =
            listOfNotNull(task.createdAt.plus(Duration.ofMinutes(config.emailWaitMinutes)), task.deadlineAt).min()
        return when {
            primaryId == null -> "no primary reviewer configured"
            primary == null -> "$primaryId was not asked to review this candidate"
            primary.status == AiResultStatus.FAILED ->
                "$primaryId failed (${primary.lastFailureKind ?: "error"}: ${primary.lastFailure.orEmpty().take(200)})"
            primary.status == AiResultStatus.EXPIRED -> "$primaryId did not answer before the decision"
            primary.status != AiResultStatus.DONE && !now.isBefore(waitUntil) ->
                "no verdict from $primaryId after ${Duration.between(task.createdAt, now).toMinutes()} min" +
                    (primary.lastFailureKind?.let { " (last failure: $it)" } ?: "")
            else -> null
        }
    }

    private suspend fun opportunity(task: AiReviewTask): SpreadOpportunity? {
        val snapshot = store.findSnapshot(task.id)
        if (snapshot == null) {
            logger.warn { "[${task.symbol}] AI review task ${task.id}: snapshot unreadable, email skipped" }
            store.saveSurfacing(task.copy(emailedAt = Instant.now(clock), emailHadVerdict = false))
            return null
        }
        return snapshot.toOpportunity()
    }

    private fun note(
        r: AiReviewResult,
        all: List<AiReviewResult>,
    ) = ReviewNote(
        reviewerId = r.reviewerId,
        verdict = r.verdict?.name,
        confidence = r.confidence,
        reason = r.reason,
        risks =
            parse(r.risksJson).map {
                ReviewRisk(it.path("category").asText("OTHER"), it.path("severity").asText(""), it.path("description").asText(""))
            },
        sources =
            parse(r.sourcesJson).mapNotNull {
                it
                    .path("title")
                    .asText("")
                    .takeIf { t -> t.isNotBlank() }
                    ?.let { t -> ReviewSource(t, it.path("url").textOrNull(), it.path("publishedAt").textOrNull()) }
            },
        newsSummary = r.newsSummary,
        model = r.model,
        late = r.late,
        otherReviewers = others(all, r.reviewerId),
    )

    private fun others(
        all: List<AiReviewResult>,
        primaryId: String?,
    ): List<String> =
        all
            .filter { it.reviewerId != primaryId && it.status == AiResultStatus.DONE }
            .map { "${it.reviewerId}: ${it.verdict} (${"%.2f".format(it.confidence ?: 0.0)})" }

    private fun telegramText(
        task: AiReviewTask,
        r: AiReviewResult,
    ): String {
        val icon =
            when (r.verdict) {
                AiVerdict.VETO -> "⛔"
                AiVerdict.APPROVE -> "✅"
                else -> "❔"
            }
        val right = if (task.strategyId == StrategyId.BULL_PUT) "P" else "C"
        val risks =
            parse(r.risksJson)
                .take(3)
                .joinToString(
                    "\n",
                ) { "• ${it.path("severity").asText("")} ${it.path("category").asText("")}: ${it.path("description").asText("")}" }
        return buildString {
            append("$icon AI ${r.verdict} (${"%.2f".format(r.confidence ?: 0.0)}) — ${task.strategyId} ${task.symbol.value} ")
            append(
                "${task.shortStrike.stripTrailingZeros().toPlainString()}/${task.longStrike.stripTrailingZeros().toPlainString()}$right ",
            )
            append("exp ${task.expiry}")
            if (r.late) append(" · LATE (after the decision)")
            append("\n\n${r.reason.orEmpty()}")
            if (risks.isNotBlank()) append("\n\n$risks")
            append("\n\n${r.reviewerId}${r.model?.let { " · $it" } ?: ""} · advisory only, no trade is blocked")
        }
    }

    private fun parse(json: String?): List<JsonNode> =
        json?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }?.filter { it.isObject } ?: emptyList()

    private fun JsonNode.textOrNull(): String? = takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

    private fun threadKey(task: AiReviewTask) = "aireview-${task.id}"

    private companion object {
        val LOOKBACK: Duration = Duration.ofDays(2)
    }
}

/** Rebuilds the candidate for the email from the frozen snapshot (the email's only input). */
internal fun ReviewSnapshotV1.toOpportunity(): SpreadOpportunity {
    val c = candidate
    val sym = Symbol(symbol)

    fun quote(leg: LegSection) =
        OptionQuote(
            contract = OptionContract(sym, c.expiry, leg.strike, OptionType.valueOf(leg.right)),
            bid = Money(leg.bid),
            ask = Money(leg.ask),
            mid = Money(leg.mid),
            greeks =
                OptionGreeks(
                    delta = leg.delta ?: Double.NaN,
                    gamma = leg.gamma ?: Double.NaN,
                    theta = leg.theta ?: Double.NaN,
                    vega = leg.vega ?: Double.NaN,
                    iv = leg.iv ?: Double.NaN,
                ),
            greeksEstimated = leg.greeksEstimated,
        )
    return SpreadOpportunity(
        strategyId = StrategyId.valueOf(strategyId),
        symbol = sym,
        session = session,
        detectedAt = asOf,
        ivRank = c.ivRank ?: iv?.rank ?: Double.NaN,
        warnings = c.warnings,
        ivRankThreshold = c.filters.ivRankThreshold,
        expiry = c.expiry,
        dte = c.dte,
        minDte = c.filters.minDte,
        maxDte = c.filters.maxDte,
        preferredDte = c.filters.preferredDte,
        targetDelta = c.filters.targetDelta,
        deltaMin = c.filters.deltaMin,
        deltaMax = c.filters.deltaMax,
        underlyingPrice = c.underlyingPrice,
        shortLeg = quote(c.shortLeg),
        longLeg = quote(c.longLeg),
        midCredit = c.midCredit,
        bidCredit = c.bidCredit,
        minCreditPerShare = c.minCreditPerShare,
        targetWidth = c.targetWidth,
        actualWidth = c.actualWidth,
        maxRiskPerShare = c.maxRiskPerShare,
        totalCapital = c.totalCapital ?: portfolio?.totalCapital ?: BigDecimal.ZERO,
        maxRiskPercent = c.filters.maxRiskPercent,
        quantity = c.quantity,
        takeProfitPercent = c.exits.takeProfitPercent,
        stopLossPercent = c.exits.stopLossPercent,
        underlyingStopEnabled = c.exits.underlyingStopEnabled,
        underlyingStopBufferPct = c.exits.underlyingStopBufferPct,
        timeProfitDte = c.exits.timeProfitDte,
        driftProtectionPct = c.exits.driftProtectionPct,
    )
}
