package cz.solvina.options.adapters.outbound.ibkr

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrConnectionHeartbeat(
    private val connectionStatusPort: ConnectionStatusPort,
) {
    @Scheduled(fixedDelayString = "\${ibkr.heartbeat.interval-ms:5000}")
    fun logConnectionStatus() {
        val status = connectionStatusPort.getConnectionStatus()
        if (status.connected) {
            logger.info { "IBKR connection OK [autoReconnect=${status.autoReconnectEnabled}]" }
        } else {
            logger.warn {
                "IBKR not connected [initialized=${status.connectionInitialized}, " +
                    "reconnecting=${status.autoReconnectThreadActive}]"
            }
        }
    }
}
