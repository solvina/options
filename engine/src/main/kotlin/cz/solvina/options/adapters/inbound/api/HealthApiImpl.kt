package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.market.MarketDataHealthTracker
import cz.solvina.options.health.api.HealthApi
import cz.solvina.options.health.dto.IbkrConnectionStatus
import cz.solvina.options.health.dto.MarketDataHealth
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping
class HealthApiImpl(
    private val connectionStatusPort: ConnectionStatusPort,
    private val marketDataHealthTracker: MarketDataHealthTracker,
) : HealthApi {
    override suspend fun getIbkrConnectionStatus(): ResponseEntity<IbkrConnectionStatus> {
        val status = connectionStatusPort.getConnectionStatus()
        return ResponseEntity.ok(
            IbkrConnectionStatus(
                connected = status.connected,
                autoReconnectEnabled = status.autoReconnectEnabled,
                autoReconnectThreadActive = status.autoReconnectThreadActive,
                connectionInitialized = status.connectionInitialized,
            ),
        )
    }

    override suspend fun getMarketDataHealth(): ResponseEntity<MarketDataHealth> {
        val s = marketDataHealthTracker.snapshot()
        return ResponseEntity.ok(
            MarketDataHealth(
                flowing = s.flowing,
                successes = s.successes,
                failures = s.failures,
                competingSession = s.competingSession,
                lastSuccessAgeSeconds = s.lastSuccessAgeSeconds,
                lastError = s.lastError,
            ),
        )
    }
}
