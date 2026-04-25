package cz.solvina.options.adapters.outbound.ibkr

import cz.solvina.options.domain.features.connection.ConnectionPort
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.models.ConnectionStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class IbkrConnectionManager(
    private val config: IbkrConnectionConfig,
    private val ibkrConnection: IbkrConnection,
) : ConnectionPort,
    ConnectionStatusPort {
    private val mutex = Mutex()
    private var initialized = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdogJob: Job? = null

    override suspend fun connect(): Boolean {
        mutex.withLock {
            if (!initialized) {
                initialized = true
                ibkrConnection.connect()
                if (config.autoReconnect) {
                    startWatchdog()
                }
            }
            return ibkrConnection.isConnected()
        }
    }

    /**
     * Watchdog runs for the lifetime of the application.
     * Each cycle: if disconnected → attempt connect; then sleep for the configured interval.
     * This handles both initial failures and mid-session drops transparently.
     */
    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return

        watchdogJob =
            scope.launch {
                while (isActive) {
                    try {
                        if (!ibkrConnection.isConnected()) {
                            logger.info { "IBKR not connected, attempting to connect..." }
                            ibkrConnection.connect()
                        }
                        delay(config.reconnectIntervalMs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "IBKR watchdog error" }
                    }
                }
            }
    }

    override fun disconnect() {
        watchdogJob?.cancel()
        watchdogJob = null
        ibkrConnection.disconnect()
        initialized = false
        logger.info { "IBKR disconnected" }
    }

    override fun isConnected(): Boolean = ibkrConnection.isConnected()

    override fun getConnectionStatus(): ConnectionStatus =
        ConnectionStatus(
            connected = ibkrConnection.isConnected(),
            autoReconnectEnabled = config.autoReconnect,
            autoReconnectThreadActive = watchdogJob?.isActive == true,
            connectionInitialized = initialized,
        )
}
