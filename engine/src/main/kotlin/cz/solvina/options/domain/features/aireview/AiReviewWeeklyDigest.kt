package cz.solvina.options.domain.features.aireview

import org.springframework.stereotype.Service
import kotlin.math.abs

/**
 * Friday after the close: the D6 report in a few lines in the "AI reviews" topic. Numbers only,
 * with sample sizes: whether they are enough to act on stays a judgement call.
 */
@Service
class AiReviewWeeklyDigest(
    private val reports: AiReviewReportService,
    private val telegram: AiReviewNotificationPort,
    private val config: AiReviewConfig,
) {
    suspend fun send() {
        if (!config.enabled) return
        telegram.announce(text(reports.report()))
    }

    internal fun text(r: AiReviewReport): String =
        buildString {
            appendLine("📊 AI review — weekly (${r.reviewerId}, all would-be trades so far)")
            appendLine()
            r.cohorts.forEach { appendLine(line(it)) }
            appendLine()
            appendLine(line(r.rulesOnly))
            appendLine(line(r.withAi))
            appendLine("Vetoes: losses avoided − wins forgone ${usd(r.lossesAvoidedMinusWinsForgone)}")
            r.approveMinusVeto?.let { d ->
                val interval =
                    if (d.low != null &&
                        d.high != null
                    ) {
                        " (90%: ${usd(d.low)} … ${usd(d.high)})"
                    } else {
                        " (too few days for an interval)"
                    }
                appendLine("APPROVE − VETO per trade ${usd(d.mean)}$interval, n=${d.approveClosed}/${d.vetoClosed}")
            }
            appendLine(
                "Reviewed titles: ${r.decisions.entries.joinToString {
                    "${it.key} ${it.value}"
                }} · gating would have blocked ${r.wouldGate}",
            )
            if (r.rulesVersions.size > 1) appendLine("Rules changed ${r.rulesVersions.size - 1}× since the first review.")
            append("Advisory only; the page Would-be Trades has the detail.")
        }

    private fun line(c: Cohort): String {
        val rate = c.winRate?.let { " · ${"%.0f".format(it * 100)}% won" }.orEmpty()
        return "${c.label}: ${c.closed} closed$rate · ${usd(c.realizedPnl)}" +
            (if (c.open > 0) " · ${c.open} open ${usd(c.markedPnl)}" else "") + " · ${c.tradingDays} days"
    }

    private fun usd(x: Double) = (if (x < 0) "−" else "") + "$" + "%.0f".format(abs(x))
}
