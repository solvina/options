package cz.solvina.options.domain.features.aireview

import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Engine-side liveness of every enabled reviewer (AI review D5b), for any provider. Alerts in the
 * engine-alerts topic once per change of health, like the IBKR watchdog: silent (no heartbeat),
 * logged out (AUTH_FAILED, reminded daily while it lasts), failing (≥ 3 failures in a row or the
 * reviewer reports DEGRADED), and one "back" message on recovery. Trading never depends on it:
 * without verdicts the candidate emails go out "AI review unavailable".
 */
@Service
class AiReviewerWatchdog(
    private val service: AiReviewService,
    private val config: AiReviewConfig,
    private val alerts: AlertPort,
    private val clock: Clock,
) {
    enum class Health { OK, SILENT, LOGGED_OUT, FAILING }

    private val last = ConcurrentHashMap<String, Health>()
    private val lastAlertAt = ConcurrentHashMap<String, Instant>()

    suspend fun check() {
        if (!config.enabled) return
        val now = Instant.now(clock)
        for (view in service.reviewers().filter { it.config.enabled }) {
            val id = view.config.id
            val health = healthOf(view)
            val previous = last.put(id, health)
            val changed = previous != health && !(previous == null && health == Health.OK)
            val reminderDue =
                health == Health.LOGGED_OUT &&
                    lastAlertAt[id]?.let { Duration.between(it, now) >= REMINDER } == true
            if (!changed && !reminderDue) continue
            lastAlertAt[id] = now
            alert(id, health, view)
        }
    }

    private fun healthOf(v: ReviewerView): Health =
        when {
            v.state == AiReviewerState.SILENT -> Health.SILENT
            v.state == AiReviewerState.AUTH_FAILED -> Health.LOGGED_OUT
            v.state == AiReviewerState.DEGRADED || (v.status?.consecutiveFailures ?: 0) >= FAILING_AFTER -> Health.FAILING
            else -> Health.OK
        }

    private suspend fun alert(
        id: String,
        health: Health,
        v: ReviewerView,
    ) {
        val detail = listOfNotNull(v.status?.message, v.status?.lastFailure).distinct().joinToString(" · ").take(500)
        when (health) {
            Health.SILENT ->
                alerts.send(
                    AlertLevel.WARN,
                    "AI reviewer $id silent",
                    "No heartbeat for over ${config.silentAfterSeconds / 60} min " +
                        "(last ${v.status?.lastHeartbeatAt ?: "never"}). Check `systemctl status ai-reviewer`. " +
                        "Candidate emails go out without a verdict meanwhile.",
                )
            Health.LOGGED_OUT ->
                alerts.send(
                    AlertLevel.CRITICAL,
                    "AI reviewer $id logged out",
                    "Its provider rejects the credentials: $detail\n" +
                        "Run `claude setup-token` and put CLAUDE_CODE_OAUTH_TOKEN in ~/options/ai-reviewer.env, " +
                        "then `sudo systemctl restart ai-reviewer`. Reminder daily while it lasts.",
                )
            Health.FAILING ->
                alerts.send(
                    AlertLevel.WARN,
                    "AI reviewer $id failing",
                    "${v.status?.consecutiveFailures ?: 0} failures in a row. Last: $detail",
                )
            Health.OK -> alerts.send(AlertLevel.INFO, "AI reviewer $id back", "State ${v.state}.")
        }
    }

    private companion object {
        const val FAILING_AFTER = 3
        val REMINDER: Duration = Duration.ofHours(24)
    }
}
