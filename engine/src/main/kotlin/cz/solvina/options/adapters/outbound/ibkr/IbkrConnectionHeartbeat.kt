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
    // Log state TRANSITIONS at INFO/WARN and steady state at DEBUG — an unconditional INFO every
    // 5s wrote ~17k journald lines/day on the Pi's SD card for zero signal. While down, re-warn
    // once a minute so an ongoing outage stays visible without spamming every tick.
    @Volatile private var lastConnected: Boolean? = null

    @Volatile private var downTicks: Int = 0

    @Scheduled(fixedDelayString = "\${ibkr.heartbeat.interval-ms:5000}")
    fun logConnectionStatus() {
        val status = connectionStatusPort.getConnectionStatus()
        val was = lastConnected
        lastConnected = status.connected
        if (status.connected) {
            downTicks = 0
            if (was != true) {
                logger.info { "IBKR connection OK [autoReconnect=${status.autoReconnectEnabled}]" }
            } else {
                logger.debug { "IBKR connection OK [autoReconnect=${status.autoReconnectEnabled}]" }
            }
        } else {
            val message = {
                "IBKR not connected [initialized=${status.connectionInitialized}, " +
                    "reconnecting=${status.autoReconnectThreadActive}]"
            }
            // Transition to down, then once per ~minute while it stays down.
            if (was != false || downTicks % 12 == 0) logger.warn(message) else logger.debug(message)
            downTicks++
        }
    }
}
