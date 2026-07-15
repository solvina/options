package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.features.market.MarketDataHealthTracker
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

private val logger = KotlinLogging.logger {}

/**
 * Turns the (already-tracked) competing-session condition into a Telegram alert, closing the
 * observability gap where IBKR 10197 "No market data during competing live session" denials only
 * ever landed in the engine logs — invisible in the UI because they're transient bursts and the
 * health badge resets to green between them.
 *
 * Behaviour: rising edge (clear → competing) alerts once; a sustained condition re-alerts at most
 * once per [RE_ALERT] so a persistent problem stays visible without spamming; falling edge
 * (competing → clear) sends a single "restored" note. The denial count in the body comes from
 * [MarketDataHealthTracker.competingCountLastHour].
 */
@Component
class CompetingSessionAlertScheduler(
    private val healthTracker: MarketDataHealthTracker,
    private val alertPort: AlertPort,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var alerting = false

    @Volatile private var lastAlertAt: Instant = Instant.EPOCH

    @Scheduled(
        fixedDelayString = "\${competing-session.check-ms:60000}",
        initialDelayString = "\${competing-session.initial-delay-ms:90000}",
    )
    fun check() {
        val competing = healthTracker.snapshot().competingSession
        val now = clock.instant()

        if (competing) {
            val elapsed = Duration.between(lastAlertAt, now)
            if (!alerting || elapsed >= RE_ALERT) {
                val count = healthTracker.competingCountLastHour()
                lastAlertAt = now
                alerting = true
                scope.launch {
                    runCatching {
                        alertPort.send(
                            AlertLevel.WARN,
                            "Competing IBKR session — market data denied",
                            "Another login on the IBKR account is stealing the market-data line " +
                                "($count denial${if (count == 1) "" else "s"} in the last hour). Quotes/greeks are " +
                                "being starved, which blocks new entries. Log out of IBKR Mobile and any other " +
                                "TWS / Client Portal session on this account.",
                        )
                    }.onFailure { e -> logger.warn(e) { "Competing-session alert failed: ${e.message}" } }
                }
            }
        } else if (alerting) {
            alerting = false
            scope.launch {
                runCatching {
                    alertPort.send(
                        AlertLevel.INFO,
                        "Competing IBKR session cleared",
                        "No competing-session denials for the last ${MarketDataHealthTracker.COMPETING_FRESH_MS / 60_000} min — market data restored.",
                    )
                }.onFailure { e -> logger.warn(e) { "Competing-session clear alert failed: ${e.message}" } }
            }
        }
    }

    companion object {
        /** Sustained competing condition re-alerts at most this often. */
        private val RE_ALERT: Duration = Duration.ofMinutes(30)
    }
}
