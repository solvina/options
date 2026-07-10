package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.account.OrphanPositionDetector
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.flag.FlagPort
import cz.solvina.options.domain.features.spread.SpreadCloserRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Periodically reconciles IBKR account positions against engine-managed OPEN spreads and
 * raises a CRITICAL alert for any orphan (a held position no OPEN spread explains). These
 * are unmanaged — no TP/SL/DTE exit runs on them — so they need a human in the loop.
 *
 * Detection only: never closes or adopts. Alerts are deduped by orphan signature and
 * re-sent at most once per [reAlertHours] while the same orphan set persists.
 */
@Component
class PositionReconciliationScheduler(
    private val spreadClosers: SpreadCloserRegistry,
    private val flagPort: FlagPort,
    private val positionsPort: PositionsPort,
    private val detector: OrphanPositionDetector,
    private val alertPort: AlertPort,
    private val connectionStatusPort: ConnectionStatusPort,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Coroutine Mutex (not a thread-bound ReentrantLock): runReconcile() suspends and may resume on
    // a different dispatcher thread, so the lock must not be thread-confined.
    private val mutex = Mutex()
    private val reAlertHours = 6L

    @Volatile private var lastSignature: String? = null

    @Volatile private var lastAlertAt: Instant = Instant.EPOCH

    @Scheduled(
        fixedDelayString = "\${reconciliation.delay-ms:300000}",
        initialDelayString = "\${reconciliation.initial-delay-ms:120000}",
    )
    fun reconcile() {
        if (!connectionStatusPort.isConnected()) {
            logger.debug { "Reconciliation skipped: IBKR not connected" }
            return
        }
        scope.launch {
            if (!mutex.tryLock()) {
                logger.debug { "Reconciliation skipped: previous run still in progress" }
                return@launch
            }
            try {
                runCatching { runReconcile() }
                    .onFailure { e -> logger.error(e) { "Position reconciliation failed: ${e.message}" } }
            } finally {
                mutex.unlock()
            }
        }
    }

    private suspend fun runReconcile() {
        // Everything the engine actively manages: open credit spreads (bull put + bear call) and
        // open bull-flag stock positions. Anything held outside this set is a true orphan.
        // CLOSING spreads still hold live legs at the broker (close orders in flight or stuck
        // retrying) and ARE managed — without them a stuck-CLOSING spread's legs get falsely
        // flagged as orphans and raise CRITICAL alerts.
        val openSpreads = spreadClosers.allOpen() + spreadClosers.allClosing()
        val openFlags = flagPort.findOpen()
        val positions = positionsPort.getPositions()
        // An empty snapshot from a feed still warming up looks identical to a flat account — deciding
        // on it would flag EVERY leg of every open position as missing. Skip the run instead.
        if (positions.isEmpty() && (openSpreads.isNotEmpty() || openFlags.isNotEmpty())) {
            logger.warn { "Reconciliation skipped: broker position snapshot empty while positions are open (feed warming up?)" }
            return
        }
        val orphans = detector.detect(openSpreads, openFlags, positions)
        val missing = detector.detectMissing(openSpreads, openFlags, positions)

        if (orphans.isEmpty() && missing.isEmpty()) {
            if (lastSignature != null) logger.info { "Reconciliation: orphans/missing legs cleared" }
            lastSignature = null
            return
        }

        val signature =
            (
                orphans.map { "${it.position.conId}:${it.position.quantity.toInt()}" } +
                    missing.map { "miss:${it.description}:${it.held}/${it.expected}" }
            ).sorted().joinToString(",")
        val now = Instant.now()
        val shouldReAlert = Duration.between(lastAlertAt, now) >= Duration.ofHours(reAlertHours)
        if (signature == lastSignature && !shouldReAlert) {
            logger.debug {
                "Reconciliation: ${orphans.size} orphan(s) + ${missing.size} missing leg(s) known, alert suppressed (deduped)"
            }
            return
        }

        lastSignature = signature
        lastAlertAt = now

        val body =
            buildString {
                if (missing.isNotEmpty()) {
                    append("${missing.size} contract(s) the engine manages are NOT (fully) held at the broker — ")
                    append("the remaining legs may be running UNHEDGED, and closing via the engine would ")
                    append("trade the absent leg (opening a new naked position):\n")
                    for (m in missing) {
                        append("\n• ${m.description} — expected ${"%+d".format(m.expected)}, held ${"%+d".format(m.held)}")
                        for (owner in m.owners) append("\n    managed by: $owner")
                    }
                    append("\n\nFix in TWS (re-buy the leg or flatten the rest) — do NOT use the dashboard Close.\n")
                }
                if (orphans.isNotEmpty()) {
                    if (missing.isNotEmpty()) append("\n")
                    append("${orphans.size} IBKR position(s) NOT managed by any OPEN spread ")
                    append("(no TP/SL/DTE exit runs on these):\n")
                    for (o in orphans) {
                        val p = o.position
                        val desc =
                            if (p.secType == "OPT") {
                                "${p.symbol} ${p.optionRight}${p.strike} exp ${p.expiry}"
                            } else {
                                "${p.symbol} ${p.secType}"
                            }
                        val pnl = p.unrealizedPnL?.let { " uPnL=%.2f".format(it) } ?: ""
                        append("\n• ${"%+d".format(p.quantity.toInt())} $desc — ${o.reason}$pnl")
                    }
                    append("\n\nReview/flatten manually in TWS — the engine will not act on these.")
                }
            }

        val title =
            when {
                missing.isNotEmpty() && orphans.isNotEmpty() ->
                    "Position mismatch: ${missing.size} missing leg(s), ${orphans.size} orphan(s)"
                missing.isNotEmpty() -> "Spread leg MISSING at broker: ${missing.size}"
                else -> "Orphaned positions: ${orphans.size} untracked"
            }
        logger.warn { "Reconciliation: ${orphans.size} orphan(s), ${missing.size} missing leg(s) — alerting" }
        alertPort.send(AlertLevel.CRITICAL, title, body)
    }
}
