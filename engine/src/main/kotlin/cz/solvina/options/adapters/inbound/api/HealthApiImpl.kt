package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/health")
class HealthApiImpl(
    private val connectionStatusPort: ConnectionStatusPort,
) : `cz.solvina.options.health`.api.HealthApi {
    override suspend fun getIbkrConnectionStatus(): ResponseEntity<`cz.solvina.options.health`.dto.IbkrConnectionStatus> {
        val status = connectionStatusPort.getConnectionStatus()
        return ResponseEntity.ok(
            `cz.solvina.options.health`.dto.IbkrConnectionStatus(
                connected = status.connected,
                autoReconnectEnabled = status.autoReconnectEnabled,
                autoReconnectThreadActive = status.autoReconnectThreadActive,
                connectionInitialized = status.connectionInitialized,
            ),
        )
    }
}
