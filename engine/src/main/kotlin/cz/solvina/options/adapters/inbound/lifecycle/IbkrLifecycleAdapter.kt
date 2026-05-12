package cz.solvina.options.adapters.inbound.lifecycle

import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrAccountAdapter
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrContractRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrHistoricalDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
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
    private val accountAdapter: IbkrAccountAdapter,
    private val historicalRegistry: IbkrHistoricalDataRegistry,
    private val contractRegistry: IbkrContractRegistry,
    private val marketDataRegistry: IbkrMarketDataRegistry,
    private val orderRegistry: IbkrOrderRegistry,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (config.enabled) {
            logger.info { "IBKR connection enabled, connecting..." }
            runBlocking {
                runCatching { connectionPort.connect() }
                    .onSuccess { connected ->
                        if (connected) {
                            logger.info { "Successfully connected to IBKR" }
                        } else {
                            logger.warn { "Could not connect to IBKR at startup — watchdog will keep retrying" }
                        }
                    }.onFailure { e ->
                        logger.warn(e) { "Error during IBKR startup connect — watchdog will keep retrying" }
                    }
            }
        } else {
            logger.info { "IBKR connection disabled, skipping startup connect" }
        }
    }

    @PreDestroy
    fun onShutdown() {
        logger.info { "Disconnecting from IBKR..." }
        val cause = RuntimeException("Application shutting down")
        historicalRegistry.cancelAllPending(cause)
        contractRegistry.cancelAllPending(cause)
        marketDataRegistry.cancelAllPending(cause)
        orderRegistry.cancelAllPending(cause)
        accountAdapter.onDisconnect()
        connectionPort.disconnect()
    }
}
