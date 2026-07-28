package cz.solvina.options.adapters.inbound.diagnostics

import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.http.MediaType
import org.springframework.stereotype.Component

@Component
@Endpoint(id = "coroutines")
class CoroutinesEndpoint(
    private val hangDiagnostics: HangDiagnostics,
) {
    @ReadOperation(produces = [MediaType.TEXT_PLAIN_VALUE])
    fun dump(): String = hangDiagnostics.buildCoroutineDump()
}
