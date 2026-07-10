package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.account.OrphanPositionDetector
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.flag.BracketOrderPort
import cz.solvina.options.domain.features.flag.FlagPort
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.report.ReportService
import cz.solvina.options.domain.features.spread.BearCallSpreadPort
import cz.solvina.options.domain.features.spread.BullPutSpreadPort
import cz.solvina.options.domain.features.spread.SpreadCloserRegistry
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

private val AUDIT_ZONE = ZoneId.of("Europe/Prague")

/** A PENDING row older than this never fills — it is a zombie the recovery paths should have resolved. */
private val PENDING_ZOMBIE_AGE: Duration = Duration.ofHours(12)

/**
 * The operator's daily checklist, automated. Two scheduled runs post a summary to the alerts
 * channel so the pre-open and post-close routine is reading one message instead of clicking
 * through pages:
 *
 *  - pre-open (default 08:45 Prague, weekdays): connection, broker-vs-DB reconciliation
 *    (orphans + missing legs), PENDING zombies, flag positions without an armed fill watcher.
 *  - post-close (default 22:15 Prague, weekdays): today's opens/closes and realized P&L per
 *    strategy (same numbers as the Reports page), plus every close that must be treated as an
 *    incident — unknown P&L or an estimated (non-fill) price — and the same reconciliation checks.
 *
 * CLEAN posts as INFO; any finding upgrades the message to WARN. Genuine emergencies are not this
 * service's job — PositionReconciliationScheduler already raises CRITICAL within minutes.
 */
@Component
class DailyAuditService(
    private val connectionStatusPort: ConnectionStatusPort,
    private val spreadClosers: SpreadCloserRegistry,
    private val bullPutSpreadPort: BullPutSpreadPort,
    private val bearCallSpreadPort: BearCallSpreadPort,
    private val flagPort: FlagPort,
    private val bracketOrderPort: BracketOrderPort,
    private val positionsPort: PositionsPort,
    private val detector: OrphanPositionDetector,
    private val reportService: ReportService,
    private val alertPort: AlertPort,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Scheduled(cron = "\${audit.pre-open-cron:0 45 8 * * MON-FRI}", zone = "Europe/Prague")
    fun preOpenAudit() {
        scope.launch {
            runCatching { runPreOpen() }
                .onFailure { e -> logger.error(e) { "Pre-open audit failed: ${e.message}" } }
        }
    }

    @Scheduled(cron = "\${audit.post-close-cron:0 15 22 * * MON-FRI}", zone = "Europe/Prague")
    fun postCloseAudit() {
        scope.launch {
            runCatching { runPostClose() }
                .onFailure { e -> logger.error(e) { "Post-close audit failed: ${e.message}" } }
        }
    }

    suspend fun runPreOpen() {
        val issues = mutableListOf<String>()
        val lines = mutableListOf<String>()

        if (!connectionStatusPort.isConnected()) {
            alertPort.send(
                AlertLevel.WARN,
                "Pre-open audit: IBKR NOT CONNECTED",
                "No broker connection — nothing else can be verified. Check TWS/gateway before the open.",
            )
            return
        }
        lines += "IBKR connected ✓"

        val openSpreads = spreadClosers.allOpen() + spreadClosers.allClosing()
        val openFlags = flagPort.findOpen()
        lines += "Open: ${openSpreads.size} spread(s), ${openFlags.size} flag(s)"

        issues += reconciliationIssues()
        issues += pendingZombieIssues()

        // Every PENDING/OPEN flag must have an armed fill watcher, or its exit fires unobserved.
        // FlagRecoveryService re-arms these itself — the audit surfaces the gap if it hasn't.
        val unwatched =
            (flagPort.findByStatus(FlagStatus.PENDING) + openFlags).filter { row ->
                val ids =
                    when (row.status) {
                        FlagStatus.PENDING -> setOf(row.entryOrderId, row.stopLossOrderId, row.profitTargetOrderId)
                        else -> setOf(row.stopLossOrderId, row.profitTargetOrderId)
                    }
                ids.none { bracketOrderPort.hasActiveWatch(it) }
            }
        if (unwatched.isNotEmpty()) {
            issues += "${unwatched.size} flag(s) without an armed fill watcher: " +
                unwatched.joinToString(", ") { it.symbol.value }
        }

        send("Pre-open audit", lines, issues)
    }

    suspend fun runPostClose() {
        val issues = mutableListOf<String>()
        val lines = mutableListOf<String>()

        val today = LocalDate.now(clock.withZone(AUDIT_ZONE))
        val report = reportService.summary(today, today)
        for (s in report.strategies) {
            if (s.opened == 0 && s.closed == 0) continue
            lines += "${s.strategy}: opened ${s.opened}, closed ${s.closed} " +
                "(${s.wins}W/${s.losses}L), P&L ${s.realizedPnl}"
        }
        if (lines.isEmpty()) lines += "No opens or closes today"
        lines += "Today total: ${report.total.realizedPnl} (${report.total.wins}W/${report.total.losses}L)"

        if (report.total.closedNoPnl > 0) {
            issues += "${report.total.closedNoPnl} close(s) today with UNKNOWN P&L (external exit) — reconcile against the broker statement"
        }

        // Closes booked from an estimate instead of a broker fill price are data-quality incidents.
        val startOfDay = today.atStartOfDay(AUDIT_ZONE).toInstant()
        val estimated =
            flagPort.findAll().filter { f ->
                f.closedAt?.isAfter(startOfDay) == true && f.closeReason?.contains("estimated") == true
            }
        if (estimated.isNotEmpty()) {
            issues += "${estimated.size} flag close(s) booked at an ESTIMATED price (no fill reported): " +
                estimated.joinToString(", ") { it.symbol.value }
        }

        if (connectionStatusPort.isConnected()) {
            issues += reconciliationIssues()
            issues += pendingZombieIssues()
        } else {
            issues += "IBKR not connected — broker reconciliation skipped"
        }

        send("Post-close audit", lines, issues)
    }

    /** Broker vs DB: orphans (held but unmanaged) and missing legs (managed but not held). */
    private suspend fun reconciliationIssues(): List<String> {
        val openSpreads = spreadClosers.allOpen() + spreadClosers.allClosing()
        val openFlags = flagPort.findOpen()
        val positions = runCatching { positionsPort.getPositions() }.getOrNull()
        if (positions == null || (positions.isEmpty() && (openSpreads.isNotEmpty() || openFlags.isNotEmpty()))) {
            return listOf("Broker position snapshot unavailable — reconciliation could not run")
        }
        val issues = mutableListOf<String>()
        val orphans = detector.detect(openSpreads, openFlags, positions)
        if (orphans.isNotEmpty()) {
            issues += "${orphans.size} orphan position(s) at broker: " +
                orphans.joinToString(", ") { "${it.position.symbol} ${"%+d".format(it.position.quantity.toInt())}" }
        }
        val missing = detector.detectMissing(openSpreads, openFlags, positions)
        if (missing.isNotEmpty()) {
            issues += "${missing.size} managed leg(s) NOT held at broker: " +
                missing.joinToString(", ") { "${it.description} (held ${it.held}/${it.expected})" }
        }
        return issues
    }

    /** PENDING rows old enough that no fill can be coming — recovery should have resolved them. */
    private suspend fun pendingZombieIssues(): List<String> {
        val cutoff = Instant.now(clock).minus(PENDING_ZOMBIE_AGE)
        val spreads =
            (bullPutSpreadPort.findByStatus(SpreadStatus.PENDING) + bearCallSpreadPort.findByStatus(SpreadStatus.PENDING))
                .filter { it.openedAt < cutoff }
        val flags = flagPort.findByStatus(FlagStatus.PENDING).filter { it.openedAt < cutoff }
        val issues = mutableListOf<String>()
        if (spreads.isNotEmpty()) {
            issues += "${spreads.size} zombie PENDING spread(s) (>12h old): " + spreads.joinToString(", ") { it.symbol.value }
        }
        if (flags.isNotEmpty()) {
            issues += "${flags.size} zombie PENDING flag(s) (>12h old): " + flags.joinToString(", ") { it.symbol.value }
        }
        return issues
    }

    private suspend fun send(
        title: String,
        lines: List<String>,
        issues: List<String>,
    ) {
        val clean = issues.isEmpty()
        val body =
            buildString {
                for (line in lines) append("$line\n")
                if (clean) {
                    append("\nAll checks passed — clean session criteria intact.")
                } else {
                    append("\nIssues (${issues.size}):\n")
                    for (issue in issues) append("\n• $issue")
                }
            }
        val verdict = if (clean) "CLEAN" else "${issues.size} issue(s)"
        logger.info { "$title: $verdict" }
        alertPort.send(if (clean) AlertLevel.INFO else AlertLevel.WARN, "$title: $verdict", body)
    }
}
