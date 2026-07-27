package cz.solvina.options.adapters.inbound.diagnostics

import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.http.MediaType
import org.springframework.stereotype.Component

/**
 * On-demand hang report over HTTP: `GET /actuator/coroutinedump`. Returns the full
 * [HangDiagnostics] report — outstanding order-fill waits, the live coroutine tree (every parked
 * `await` / `Mutex`), and a JVM thread dump. Unlike the watchdog (which only auto-fires on a stuck
 * order-fill wait), this captures the state wherever the hang is — pull it the moment anything
 * looks wedged. Read-only; safe to call anytime.
 */
@Component
@Endpoint(id = "coroutinedump")
class CoroutineDumpEndpoint(
    private val hangDiagnostics: HangDiagnostics,
) {
    @ReadOperation(produces = [MediaType.TEXT_PLAIN_VALUE])
    fun dump(): String = hangDiagnostics.buildReport("on-demand /actuator/coroutinedump")
}
