package cz.solvina.options.domain.features.aireview

import cz.solvina.options.domain.features.shadow.ShadowSpread
import cz.solvina.options.domain.features.shadow.ShadowSpreadPort
import cz.solvina.options.domain.features.shadow.ShadowStatus
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

/** One group of would-be trades and how it did (USD per contract at mid). */
data class Cohort(
    val label: String,
    val count: Int,
    val closed: Int,
    val wins: Int,
    val realizedPnl: Double,
    val open: Int,
    val markedPnl: Double,
    val tradingDays: Int,
) {
    val winRate: Double? get() = if (closed > 0) wins.toDouble() / closed else null
    val avgPnl: Double? get() = if (closed > 0) realizedPnl / closed else null
}

data class Difference(
    val mean: Double,
    val low: Double?,
    val high: Double?,
    val approveClosed: Int,
    val vetoClosed: Int,
)

data class CalibrationBucket(
    val low: Double,
    val high: Double,
    val closed: Int,
    val right: Int,
)

data class RulesVersionMark(
    val version: String,
    val firstSeenAt: Instant,
    val tasks: Int,
)

data class AiReviewReport(
    val reviewerId: String,
    val generatedAt: Instant,
    val from: LocalDate?,
    val rulesVersion: String?,
    val cohorts: List<Cohort>,
    val rulesOnly: Cohort,
    val withAi: Cohort,
    val removedByAi: Cohort,
    val addedByAi: Cohort,
    val lossesAvoidedMinusWinsForgone: Double,
    val approveMinusVeto: Difference?,
    val calibration: List<CalibrationBucket>,
    val rulesVersions: List<RulesVersionMark>,
    val decisions: Map<String, Int>,
    val wouldGate: Int,
)

/**
 * The D6 evaluation: how the primary reviewer's verdicts relate to the would-be trades. Analysis
 * only, never read by a decision. Scored on every ranked live would-be trade from a pool decision
 * (decided 2026-11-19: the small-account `selected` portfolio is too sparse to score on), grouped
 * by the verdict the decision saw. Evaluation stays fluid: the report shows sample sizes and an
 * interval with trading days as clusters (same-day spreads move together), not a pass mark.
 */
@Service
class AiReviewReportService(
    private val shadows: ShadowSpreadPort,
    private val store: AiReviewStorePort,
    private val config: AiReviewConfig,
    private val clock: Clock,
) {
    suspend fun report(
        from: LocalDate? = null,
        rulesVersion: String? = null,
    ): AiReviewReport {
        val reviews = store.findReviewsSince(Instant.EPOCH)
        val taskById = reviews.associateBy { it.task.id }
        val primaryId = config.primaryReviewer?.id ?: "-"
        val rows =
            shadows
                .findAll()
                .filter { !it.backfilled && it.score != null }
                .filter {
                    from == null ||
                        !it.openedAt
                            .atZone(NEW_YORK)
                            .toLocalDate()
                            .isBefore(from)
                }.filter { rulesVersion == null || it.aiTaskId?.let { id -> taskById[id]?.task?.rulesVersion } == rulesVersion }

        val cohorts =
            rows
                .groupBy { it.verdictLabel() }
                .toSortedMap(compareBy { COHORT_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: COHORT_ORDER.size })
                .map { (label, group) -> cohort(label, group) }
        val vetoed = rows.filter { it.aiVerdict == AiVerdict.VETO.name && it.isClosed() }
        val confidenceByTask =
            reviews.associate { r -> r.task.id to r.results.firstOrNull { it.reviewerId == primaryId }?.confidence }
        val scopedTasks =
            reviews
                .map { it.task }
                .filter { it.status == AiTaskStatus.OPEN }
                .filter { from == null || !it.tradingDate.isBefore(from) }
                .filter { rulesVersion == null || it.rulesVersion == rulesVersion }
        return AiReviewReport(
            reviewerId = primaryId,
            generatedAt = Instant.now(clock),
            from = from,
            rulesVersion = rulesVersion,
            cohorts = cohorts,
            rulesOnly = cohort("Rules only (selected)", rows.filter { it.selected }),
            withAi = cohort("Rules + AI (ai_selected)", rows.filter { it.aiSelected }),
            removedByAi = cohort("Selected, removed by the AI", rows.filter { it.selected && !it.aiSelected }),
            addedByAi = cohort("Taken by the AI instead", rows.filter { it.aiSelected && !it.selected }),
            lossesAvoidedMinusWinsForgone = -vetoed.sumOf { it.pnl() },
            approveMinusVeto = difference(rows),
            calibration = calibration(rows, confidenceByTask),
            rulesVersions =
                reviews
                    .map { it.task }
                    .filter { it.rulesVersion != null }
                    .groupBy { it.rulesVersion!! }
                    .map { (v, ts) -> RulesVersionMark(v, ts.minOf { it.createdAt }, ts.size) }
                    .sortedBy { it.firstSeenAt },
            decisions = scopedTasks.groupingBy { it.decision?.action ?: "UNDECIDED" }.eachCount(),
            wouldGate = scopedTasks.count { it.decision?.wouldGate == true },
        )
    }

    private fun ShadowSpread.verdictLabel(): String = aiVerdict ?: if (aiTaskId != null) NO_VERDICT_IN_TIME else NOT_REVIEWED

    private fun ShadowSpread.isClosed() = status != ShadowStatus.OPEN && exitValue != null

    /** Realized (closed) or marked (open) USD per contract at mid; 0 when never valued. */
    private fun ShadowSpread.pnl(): Double {
        val value = (if (isClosed()) exitValue else lastValue) ?: return 0.0
        return midCredit
            .subtract(value)
            .multiply(HUNDRED)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }

    private fun ShadowSpread.day(): LocalDate = openedAt.atZone(NEW_YORK).toLocalDate()

    private fun cohort(
        label: String,
        rows: List<ShadowSpread>,
    ): Cohort {
        val closed = rows.filter { it.isClosed() }
        val open = rows.filter { !it.isClosed() }
        return Cohort(
            label = label,
            count = rows.size,
            closed = closed.size,
            wins = closed.count { it.pnl() > 0 },
            realizedPnl = closed.sumOf { it.pnl() },
            open = open.size,
            markedPnl = open.sumOf { it.pnl() },
            tradingDays = rows.map { it.day() }.distinct().size,
        )
    }

    /**
     * Mean realized P&L per APPROVE trade minus per VETO trade, with a 90% interval from a
     * bootstrap that resamples whole trading days (fixed seed: the same data gives the same
     * report). No interval until each side has trades on at least two days.
     */
    private fun difference(rows: List<ShadowSpread>): Difference? {
        val closed = rows.filter { it.isClosed() && it.aiVerdict in setOf(AiVerdict.APPROVE.name, AiVerdict.VETO.name) }
        val approve = closed.filter { it.aiVerdict == AiVerdict.APPROVE.name }
        val veto = closed.filter { it.aiVerdict == AiVerdict.VETO.name }
        if (approve.isEmpty() || veto.isEmpty()) return null

        fun meanDiff(sample: List<ShadowSpread>): Double? {
            val a = sample.filter { it.aiVerdict == AiVerdict.APPROVE.name }
            val v = sample.filter { it.aiVerdict == AiVerdict.VETO.name }
            if (a.isEmpty() || v.isEmpty()) return null
            return a.sumOf { it.pnl() } / a.size - v.sumOf { it.pnl() } / v.size
        }
        val mean = meanDiff(closed)!!
        val days = closed.groupBy { it.day() }.values.toList()
        val enoughDays = approve.map { it.day() }.distinct().size >= 2 && veto.map { it.day() }.distinct().size >= 2
        if (!enoughDays) return Difference(mean, null, null, approve.size, veto.size)
        val random = Random(BOOTSTRAP_SEED)
        val samples =
            (1..BOOTSTRAP_ROUNDS)
                .mapNotNull { meanDiff(List(days.size) { days[random.nextInt(days.size)] }.flatten()) }
                .sorted()
        val low = samples[(samples.size * 0.05).toInt()]
        val high = samples[(samples.size * 0.95).toInt().coerceAtMost(samples.size - 1)]
        return Difference(mean, low, high, approve.size, veto.size)
    }

    /** Does a confident verdict turn out right more often? APPROVE right = won; VETO right = lost. */
    private fun calibration(
        rows: List<ShadowSpread>,
        confidenceByTask: Map<UUID, Double?>,
    ): List<CalibrationBucket> {
        val judged =
            rows.filter { it.isClosed() && it.aiVerdict in setOf(AiVerdict.APPROVE.name, AiVerdict.VETO.name) }
        return BUCKETS.zipWithNext().map { (low, high) ->
            val inBucket =
                judged.filter {
                    val c = it.aiConfidence ?: it.aiTaskId?.let { id -> confidenceByTask[id] } ?: return@filter false
                    c >= low && (c < high || (high == 1.0 && c <= high))
                }
            val right =
                inBucket.count {
                    (it.aiVerdict == AiVerdict.APPROVE.name && it.pnl() > 0) || (it.aiVerdict == AiVerdict.VETO.name && it.pnl() <= 0)
                }
            CalibrationBucket(low, high, inBucket.size, right)
        }
    }

    companion object {
        const val NO_VERDICT_IN_TIME = "NO_VERDICT_IN_TIME"
        const val NOT_REVIEWED = "NOT_REVIEWED"
        private val COHORT_ORDER = listOf("APPROVE", "VETO", "ABSTAIN", NO_VERDICT_IN_TIME, NOT_REVIEWED)
        private val BUCKETS = listOf(0.0, 0.6, 0.75, 0.9, 1.0)
        private val HUNDRED = BigDecimal(100)
        private const val BOOTSTRAP_ROUNDS = 2000
        private const val BOOTSTRAP_SEED = 20261119
    }
}
