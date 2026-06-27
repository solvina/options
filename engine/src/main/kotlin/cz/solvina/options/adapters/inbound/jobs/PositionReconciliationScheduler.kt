package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.account.OrphanPositionDetector
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.spread.BullPutSpreadPort
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
    private val spreadPort: BullPutSpreadPort,
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
        val openSpreads = spreadPort.findOpen()
        val positions = positionsPort.getPositions()
        val orphans = detector.detect(openSpreads, positions)

        if (orphans.isEmpty()) {
            if (lastSignature != null) logger.info { "Reconciliation: orphans cleared" }
            lastSignature = null
            return
        }

        val signature = orphans.map { "${it.position.conId}:${it.position.quantity.toInt()}" }.sorted().joinToString(",")
        val now = Instant.now()
        val shouldReAlert = Duration.between(lastAlertAt, now) >= Duration.ofHours(reAlertHours)
        if (signature == lastSignature && !shouldReAlert) {
            logger.debug { "Reconciliation: ${orphans.size} known orphan(s), alert suppressed (deduped)" }
            return
        }

        lastSignature = signature
        lastAlertAt = now

        val body =
            buildString {
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

        logger.warn { "Reconciliation: ${orphans.size} orphaned position(s) detected — alerting" }
        alertPort.send(AlertLevel.CRITICAL, "Orphaned positions: ${orphans.size} untracked", body)
    }
}
