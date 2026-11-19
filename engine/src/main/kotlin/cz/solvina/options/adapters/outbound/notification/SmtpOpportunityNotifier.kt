package cz.solvina.options.adapters.outbound.notification

import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.notification.OpportunityNotificationPort
import cz.solvina.options.domain.features.notification.ReviewNote
import cz.solvina.options.domain.features.notification.SpreadOpportunity
import cz.solvina.options.domain.models.OptionType
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Emails a rich summary of every qualified spread candidate via Gmail SMTP (STARTTLS on :587).
 * Best-effort and non-throwing per [OpportunityNotificationPort]: a mail failure is logged and
 * swallowed so it never breaks or delays the scan loop. No-op when disabled or unconfigured.
 *
 * SECURITY: the App Password is never logged — only the subject/recipient on failure.
 */
@Component
class SmtpOpportunityNotifier(
    private val config: EmailNotificationConfig,
    private val clock: Clock,
) : OpportunityNotificationPort {
    private val tsFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())

    /** Last time each opportunity key was emailed — powers the dedupe window. */
    private val lastSentAt = ConcurrentHashMap<String, Instant>()

    override suspend fun notify(opportunity: SpreadOpportunity) {
        if (!configured()) {
            logger.debug { "Opportunity email suppressed (disabled/unconfigured): ${opportunity.symbol.value}" }
            return
        }

        if (isDuplicate(opportunity)) {
            logger.debug { "Opportunity email suppressed (already sent within ${config.dedupeMinutes}m): ${dedupeKey(opportunity)}" }
            return
        }

        send(subjectFor(opportunity), plainBody(opportunity), htmlBody(opportunity))
    }

    override suspend fun notifyReviewed(
        opportunity: SpreadOpportunity,
        review: ReviewNote,
        threadKey: String,
    ) {
        if (!configured()) return
        lastSentAt[dedupeKey(opportunity)] = Instant.now(clock) // a later plain email of the same spread is a duplicate
        send(
            subject = reviewedSubject(opportunity, review),
            plain = plainReview(review) + "\n" + plainBody(opportunity),
            html = htmlBody(opportunity, htmlReview(review)),
            messageId = messageId(threadKey),
        )
    }

    override suspend fun notifyReviewFollowUp(
        opportunity: SpreadOpportunity,
        review: ReviewNote,
        threadKey: String,
    ) {
        if (!configured()) return
        val original = reviewedSubject(opportunity, review.copy(verdict = null))
        val o = opportunity
        val intro = "AI verdict for ${o.symbol.value} ${o.strategyLabel}, which arrived after the candidate email:"
        send(
            subject = "Re: $original",
            plain = intro + "\n\n" + plainReview(review),
            html = "<div style=\"font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;max-width:720px\"><p>${esc(
                intro,
            )}</p>${htmlReview(review)}</div>",
            inReplyTo = messageId(threadKey),
        )
    }

    private fun configured(): Boolean =
        config.enabled && config.username.isNotBlank() && config.password.isNotBlank() && config.to.isNotBlank()

    private fun messageId(threadKey: String) = "<$threadKey@options-engine>"

    private suspend fun send(
        subject: String,
        plain: String,
        html: String,
        messageId: String? = null,
        inReplyTo: String? = null,
    ) {
        try {
            withContext(Dispatchers.IO) {
                val message =
                    ThreadedMessage(buildSession(), messageId).apply {
                        setFrom(InternetAddress(config.from.ifBlank { config.username }))
                        setRecipients(Message.RecipientType.TO, InternetAddress.parse(config.to))
                        setSubject(subject, "utf-8")
                        if (inReplyTo != null) {
                            setHeader("In-Reply-To", inReplyTo)
                            setHeader("References", inReplyTo)
                        }
                        val text = MimeBodyPart().apply { setText(plain, "utf-8") }
                        val htmlPart = MimeBodyPart().apply { setContent(html, "text/html; charset=utf-8") }
                        setContent(
                            MimeMultipart().apply {
                                addBodyPart(text)
                                addBodyPart(htmlPart)
                            },
                        )
                    }
                Transport.send(message)
            }
            logger.info { "Opportunity email sent to ${config.to}: $subject" }
        } catch (e: Exception) {
            logger.warn { "Opportunity email '$subject' to ${config.to} failed: ${e.message}" }
        }
    }

    /** Fixed Message-ID so a follow-up can reply into the candidate's thread. */
    private class ThreadedMessage(
        session: Session,
        private val fixedId: String?,
    ) : MimeMessage(session) {
        override fun updateMessageID() {
            if (fixedId != null) setHeader("Message-ID", fixedId) else super.updateMessageID()
        }
    }

    private fun reviewedSubject(
        o: SpreadOpportunity,
        review: ReviewNote,
    ): String {
        val tag =
            when (review.verdict) {
                "VETO" -> "⛔ AI VETO"
                "APPROVE" -> "✅ AI OK"
                "ABSTAIN" -> "❔ AI no view"
                else -> "❔ AI unavailable"
            }
        return "$tag · " + subjectFor(o)
    }

    private fun verdictLine(r: ReviewNote): String =
        if (r.verdict == null) {
            "AI review unavailable: ${r.unavailableReason ?: "no verdict"}"
        } else {
            "AI verdict: ${r.verdict} (confidence ${"%.2f".format(r.confidence ?: 0.0)})" +
                (if (r.late) " — LATE, after the decision" else "") +
                " · ${r.reviewerId}${r.model?.let { " / $it" } ?: ""}"
        }

    private fun plainReview(r: ReviewNote): String =
        buildString {
            appendLine("AI REVIEW (advisory only: it does not block or place trades)")
            appendLine("  ${verdictLine(r)}")
            r.reason?.let { appendLine("  $it") }
            if (r.risks.isNotEmpty()) {
                appendLine("  Risks:")
                r.risks.forEach { appendLine("   - [${it.severity}] ${it.category}: ${it.description}") }
            }
            r.newsSummary?.let { appendLine("  News: $it") }
            if (r.sources.isNotEmpty()) {
                appendLine("  Sources:")
                r.sources.take(MAX_SOURCES).forEach {
                    appendLine(
                        "   - ${it.title}${it.publishedAt?.let { d ->
                            " ($d)"
                        } ?: ""}${it.url?.let { u -> " $u" } ?: ""}",
                    )
                }
            }
            r.otherReviewers.forEach { appendLine("  Also: $it") }
        }

    private fun htmlReview(r: ReviewNote): String {
        val color =
            when (r.verdict) {
                "VETO" -> "#b91c1c"
                "APPROVE" -> "#15803d"
                else -> "#6b7280"
            }
        val risks =
            if (r.risks.isEmpty()) {
                ""
            } else {
                "<ul style=\"margin:6px 0\">" +
                    r.risks.joinToString("") { "<li><b>${esc(it.severity)}</b> ${esc(it.category)}: ${esc(it.description)}</li>" } + "</ul>"
            }
        val sources =
            if (r.sources.isEmpty()) {
                ""
            } else {
                "<div style=\"font-size:12px;color:#555\">Sources: " +
                    r.sources.take(MAX_SOURCES).joinToString(" &middot; ") { s ->
                        val label = esc(s.title) + (s.publishedAt?.let { " (${esc(it.take(10))})" } ?: "")
                        s.url?.takeIf { it.startsWith("http") }?.let { "<a href=\"${esc(it)}\">$label</a>" } ?: label
                    } + "</div>"
            }
        val news = r.newsSummary?.let { "<p style=\"margin:6px 0;color:#333\"><b>News:</b> ${esc(it)}</p>" }.orEmpty()
        val others = r.otherReviewers.joinToString("") { "<div style=\"font-size:12px\">Also: ${esc(it)}</div>" }
        return """
            <div style="border-left:4px solid $color;padding:8px 12px;margin:0 0 16px;background:#fafafa">
              <div style="font-weight:600;color:$color">${esc(verdictLine(r))}</div>
              ${r.reason?.let { "<p style=\"margin:6px 0\">${esc(it)}</p>" }.orEmpty()}
              $risks$news$sources$others
              <div style="font-size:11px;color:#888;margin-top:6px">Advisory only: the AI review does not block or place trades.</div>
            </div>
            """.trimIndent()
    }

    private fun esc(s: String): String =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    /** Stable identity of an opportunity — the same spread re-found on a later scan shares this key. */
    private fun dedupeKey(o: SpreadOpportunity): String =
        "${o.strategyId}:${o.symbol.value}:${o.shortLeg.contract.strike.stripTrailingZeros().toPlainString()}/" +
            "${o.longLeg.contract.strike.stripTrailingZeros().toPlainString()}:${o.expiry}"

    /**
     * True if this opportunity was already emailed within the dedupe window. Atomic per key
     * (ConcurrentHashMap.compute) so concurrent duplicate launches collapse to a single send.
     */
    private fun isDuplicate(o: SpreadOpportunity): Boolean {
        if (config.dedupeMinutes <= 0) return false
        val now = Instant.now(clock)
        val window = Duration.ofMinutes(config.dedupeMinutes)
        var duplicate = false
        lastSentAt.compute(dedupeKey(o)) { _, previous ->
            if (previous != null && Duration.between(previous, now) < window) {
                duplicate = true
                previous // keep the original send time; suppress this one
            } else {
                now // first send, or window elapsed — record and allow
            }
        }
        // Opportunistic prune so the map can't grow unbounded across a long-running session.
        if (lastSentAt.size > 256) {
            lastSentAt.entries.removeIf { Duration.between(it.value, now) >= window }
        }
        return duplicate
    }

    private fun buildSession(): Session {
        val props =
            Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host", config.smtpHost)
                put("mail.smtp.port", config.smtpPort.toString())
                put("mail.smtp.connectiontimeout", "10000")
                put("mail.smtp.timeout", "10000")
                put("mail.smtp.writetimeout", "10000")
            }
        return Session.getInstance(
            props,
            object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(config.username, config.password)
            },
        )
    }

    private fun subjectFor(o: SpreadOpportunity): String {
        val right = if (o.shortLeg.contract.type == OptionType.PUT) "P" else "C"
        val prefix = if (o.warnings.isEmpty()) "🎯" else "⚠️ REVIEW (${o.warnings.joinToString()}) —"
        return "$prefix ${o.strategyLabel} candidate — ${o.symbol.value}  " +
            "${strike(o.shortLeg.contract.strike)}/${strike(o.longLeg.contract.strike)}$right  " +
            "${o.dte} DTE  credit \$${money(o.midCredit)}  (IV rank ${pct(o.ivRank)})"
    }

    private fun plainBody(o: SpreadOpportunity): String {
        val right = if (o.shortLeg.contract.type == OptionType.PUT) "P" else "C"
        val s = o.shortLeg
        val l = o.longLeg
        return buildString {
            appendLine("${o.strategyLabel} spread — opportunity found")
            appendLine("${o.symbol.value} (${o.session} session) · ${tsFormat.format(o.detectedAt)} · scan-triggered candidate")
            appendLine()
            if (o.warnings.isNotEmpty()) {
                appendLine("WARNINGS: ${o.warnings.joinToString()} — the engine will not trade this automatically.")
                appendLine()
            }
            appendLine("WHY IT QUALIFIED")
            appendLine("  IV Rank:          ${pct(o.ivRank)}  (threshold ≥ ${pct(o.ivRankThreshold)})")
            appendLine("  Expiry / DTE:     ${o.expiry} · ${o.dte} DTE  (band ${o.minDte}–${o.maxDte}, prefer ${o.preferredDte})")
            appendLine(
                "  Short-leg delta:  ${delta(s)}  " +
                    "(target ${dec(o.targetDelta)}, band ${dec(o.deltaMin)}–${dec(o.deltaMax)})",
            )
            appendLine("  Underlying:       \$${money(o.underlyingPrice)}")
            appendLine()
            appendLine("THE SPREAD (per 1 contract = 100 shares, qty ${o.quantity})")
            appendLine(
                "  SELL ${strike(s.contract.strike)}$right   delta ${delta(s)}  iv ${ivPct(s)}  " +
                    "theta ${greek(s.greeks.theta)}  gamma ${greek(s.greeks.gamma)}  vega ${greek(s.greeks.vega)}",
            )
            appendLine("       bid/ask/mid: ${money(s.bid.amount)} / ${money(s.ask.amount)} / ${money(s.mid.amount)}")
            appendLine(
                "  BUY  ${strike(l.contract.strike)}$right   delta ${delta(l)}  iv ${ivPct(l)}  " +
                    "theta ${greek(l.greeks.theta)}  gamma ${greek(l.greeks.gamma)}  vega ${greek(l.greeks.vega)}",
            )
            appendLine("       bid/ask/mid: ${money(l.bid.amount)} / ${money(l.ask.amount)} / ${money(l.mid.amount)}")
            appendLine()
            appendLine("PRICING & RISK")
            appendLine("  Net credit (mid/target):     \$${money(o.midCredit)} /share → \$${money(o.maxProfitPerContract)}")
            appendLine("  Achievable combo bid (floor): \$${money(o.bidCredit)} /share  (min floor \$${money(o.minCreditPerShare)})")
            appendLine("  Suggested resting limit:      ${limitText(o)}")
            appendLine("  Spread width:                 \$${money(o.targetWidth)} target / \$${money(o.actualWidth)} actual")
            appendLine("  Max risk / share:             \$${money(o.maxRiskPerShare)} → max loss \$${money(o.maxLossPerContract)}")
            appendLine("  Max profit:                   \$${money(o.maxProfitPerContract)}")
            appendLine("  Break-even:                   \$${money(o.breakEven)}")
            appendLine(
                "  Allowed risk this trade:      \$${money(o.allowedRiskPerTrade)}  " +
                    "(capital \$${money(o.totalCapital)} × ${pct(o.maxRiskPercent * 100)})",
            )
            appendLine()
            appendLine("PLANNED MANAGEMENT (from config)")
            appendLine(
                "  Take-profit at ${pct(o.takeProfitPercent * 100)} of credit · ${stopText(o)} · " +
                    "Time-exit ${o.timeProfitDte} DTE · Drift protection ${pct(o.driftProtectionPct * 100)}",
            )
            appendLine()
            appendLine(
                "This candidate qualified now. The engine ranks the day's candidates at the decision times, so an order is not guaranteed.",
            )
            appendLine("Values are the live streaming quotes/greeks used in the decision.")
        }
    }

    private fun htmlBody(
        o: SpreadOpportunity,
        reviewHtml: String = "",
    ): String {
        val right = if (o.shortLeg.contract.type == OptionType.PUT) "P" else "C"
        val s = o.shortLeg
        val l = o.longLeg

        val whyTable =
            table(
                listOf("Filter", "Value", "Threshold / target"),
                listOf(
                    listOf("IV Rank", "<b>${pct(o.ivRank)}</b>", "&ge; ${pct(o.ivRankThreshold)}"),
                    listOf("Expiry / DTE", "${o.expiry} &middot; <b>${o.dte} DTE</b>", "${o.minDte}-${o.maxDte}, prefer ${o.preferredDte}"),
                    listOf(
                        "Short-leg delta",
                        "<b>${delta(s)}</b>",
                        "target ${dec(o.targetDelta)} (${dec(o.deltaMin)}-${dec(o.deltaMax)})",
                    ),
                    listOf("Underlying price", "\$${money(o.underlyingPrice)}", "drift anchor"),
                ),
            )

        val spreadRow = { leg: String, action: String, q: OptionQuote ->
            listOf(
                leg,
                action,
                "\$${strike(q.contract.strike)} $right",
                delta(q),
                ivPct(q),
                greek(q.greeks.theta),
                "${money(q.bid.amount)} / ${money(q.ask.amount)} / <b>${money(q.mid.amount)}</b>",
            )
        }
        val spreadTable =
            table(
                listOf("Leg", "Action", "Strike", "Delta", "IV", "Theta", "Bid / Ask / Mid"),
                listOf(spreadRow("Short", "<b>SELL</b>", s), spreadRow("Long", "<b>BUY</b>", l)),
            )

        val creditCell = "<b>\$${money(o.midCredit)}</b>/share &rarr; \$${money(o.maxProfitPerContract)}"
        val riskCell = "<b>\$${money(o.maxRiskPerShare)}</b> &rarr; max loss <b>\$${money(o.maxLossPerContract)}</b>"
        val allowedCell = "\$${money(o.allowedRiskPerTrade)} (capital \$${money(o.totalCapital)} &times; ${pct(o.maxRiskPercent * 100)})"
        val pricingTable =
            table(
                listOf("Metric", "Value"),
                listOf(
                    listOf("Net credit (mid / target)", creditCell),
                    listOf("Achievable combo bid (floor)", "\$${money(o.bidCredit)}/share (min floor \$${money(o.minCreditPerShare)})"),
                    listOf("Suggested resting limit", "<b>${limitText(o)}</b>"),
                    listOf("Spread width", "\$${money(o.targetWidth)} target / \$${money(o.actualWidth)} actual"),
                    listOf("Max risk / share", riskCell),
                    listOf("Max profit", "\$${money(o.maxProfitPerContract)}"),
                    listOf("Break-even", "<b>\$${money(o.breakEven)}</b>"),
                    listOf("Allowed risk this trade", allowedCell),
                ),
            )

        val management =
            "Take-profit at ${pct(o.takeProfitPercent * 100)} of credit &middot; ${stopText(o)} " +
                "&middot; Time-exit at ${o.timeProfitDte} DTE &middot; Drift protection ${pct(o.driftProtectionPct * 100)}"

        return """
            <div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1a1a1a;max-width:720px">
              <h2 style="margin:0 0 4px">${o.strategyLabel} spread — opportunity found</h2>
              <div style="color:#666;margin-bottom:16px">
                <b>${o.symbol.value}</b> (${o.session} session) &middot; ${tsFormat.format(o.detectedAt)} &middot; scan-triggered candidate
              </div>
              $reviewHtml
              ${if (o.warnings.isEmpty()) "" else "<p style=\"color:#b45309;font-weight:600\">Warnings: ${o.warnings.joinToString()} — the engine will not trade this automatically.</p>"}
              <h3 style="margin:16px 0 6px">Why it qualified</h3>
              $whyTable
              <h3 style="margin:16px 0 6px">The spread</h3>
              $spreadTable
              <h3 style="margin:16px 0 6px">Pricing &amp; risk (per 1 contract x100, qty ${o.quantity})</h3>
              $pricingTable
              <h3 style="margin:16px 0 6px">Planned management (from config)</h3>
              <p style="margin:0">$management</p>
              <p style="color:#888;font-size:12px;margin-top:20px">
                This candidate qualified now. The engine ranks the day's candidates at the decision times, so an order is not guaranteed.
                Values are the live streaming quotes/greeks used in the decision.
              </p>
            </div>
            """.trimIndent()
    }

    private fun table(
        headers: List<String>,
        rows: List<List<String>>,
    ): String {
        val tableStyle = "border-collapse:collapse;width:100%;font-size:14px"
        val thStyle = "text-align:left;padding:6px 10px;border-bottom:2px solid #ddd"
        val tdStyle = "padding:6px 10px;border-bottom:1px solid #eee"
        val head = headers.joinToString("") { "<th style=\"$thStyle\">$it</th>" }
        val body =
            rows.joinToString("") { row ->
                "<tr>" + row.joinToString("") { "<td style=\"$tdStyle\">$it</td>" } + "</tr>"
            }
        return "<table style=\"$tableStyle\"><thead><tr>$head</tr></thead><tbody>$body</tbody></table>"
    }

    private fun money(v: BigDecimal): String = v.setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun strike(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()

    private fun pct(v: Double): String = "%.1f%%".format(v)

    private fun dec(v: Double): String = "%.2f".format(v)

    private fun greek(v: Double): String = if (v.isFinite()) "%.3f".format(v) else "n/a"

    /** Delta with an "est." marker when IBKR sent no greeks and they were estimated from neighbours. */
    private fun delta(q: OptionQuote): String = greek(q.greeks.delta) + if (q.greeksEstimated) " (est.)" else ""

    private fun ivPct(q: OptionQuote): String = if (q.greeks.iv.isFinite()) pct(q.greeks.iv * 100) else "n/a"

    private fun stopText(o: SpreadOpportunity): String {
        val trigger = o.underlyingStopTrigger
        val side = if (o.isBullPut) "≤" else "≥"
        val underlying =
            if (trigger == null) {
                "underlying stop off"
            } else {
                "underlying $side \$${money(trigger)}" +
                    if (o.underlyingStopBufferPct >
                        0
                    ) {
                        " (${pct(o.underlyingStopBufferPct * 100)} before short strike)"
                    } else {
                        " (short strike)"
                    }
            }
        val value =
            if (o.stopLossPercent >
                0
            ) {
                "option-price stop at ${"%.2f".format(1 + o.stopLossPercent)}× entry mid"
            } else {
                "option-price stop off"
            }
        return "Stop: $underlying; $value"
    }

    private fun limitText(o: SpreadOpportunity): String =
        "\$${money(o.suggestedLimit)} credit" +
            (o.fairCredit?.let { " (fair value \$${money(it)}, mid \$${money(o.midCredit)})" } ?: " (mid; no model value)")

    private companion object {
        const val MAX_SOURCES = 8
    }
}
