package cz.solvina.options.domain.features.fatal

import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

data class FatalReason(
    val title: String,
    val detail: String,
    val at: Instant,
)

/**
 * Engine-wide fatal lockout. A triggered fatal means the engine's configuration or environment is
 * wrong in a way that makes trading unsafe (e.g. the connected IBKR account is not the configured
 * one) — order placement is blocked at the socket (see GuardedEClientSocket) until the operator
 * fixes the cause and restarts. Deliberately latching: there is no programmatic clear.
 *
 * Every trigger is an ERROR log + CRITICAL alert, and the state is exposed on /health/fatal so the
 * frontend can show it at first glance.
 */
@Component
class FatalLockoutService(
    private val alertPort: AlertPort,
    private val alertScope: CoroutineScope,
) {
    private val fatalReasons = CopyOnWriteArrayList<FatalReason>()

    val reasons: List<FatalReason> get() = fatalReasons

    val isFatal: Boolean get() = fatalReasons.isNotEmpty()

    fun trigger(
        title: String,
        detail: String,
    ) {
        if (fatalReasons.any { it.title == title }) return
        fatalReasons += FatalReason(title, detail, Instant.now())
        logger.error { "FATAL LOCKOUT: $title — $detail. Order placement is DISABLED until restart with fixed configuration." }
        alertScope.launch {
            alertPort.send(
                AlertLevel.CRITICAL,
                "FATAL: $title",
                "$detail\n\nOrder placement is DISABLED. Fix the configuration and restart the engine.",
            )
        }
    }
}
