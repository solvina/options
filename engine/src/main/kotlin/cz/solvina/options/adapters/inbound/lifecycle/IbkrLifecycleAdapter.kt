package cz.solvina.options.adapters.inbound.lifecycle

import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.domain.features.connection.ConnectionPort
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.runBlocking
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrLifecycleAdapter(
    private val config: IbkrConnectionConfig,
    private val connectionPort: ConnectionPort,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (config.enabled) {
            logger.info { "IBKR connection enabled, connecting..." }
            runBlocking {
                val connected = connectionPort.connect()
                if (connected) {
                    logger.info { "Successfully connected to IBKR" }
                } else {
                    logger.warn { "Failed to connect to IBKR, application continues" }
                }
            }
        } else {
            logger.info { "IBKR connection disabled, skipping startup connect" }
        }
    }

    @PreDestroy
    fun onShutdown() {
        logger.info { "Disconnecting from IBKR..." }
        connectionPort.disconnect()
    }
}
