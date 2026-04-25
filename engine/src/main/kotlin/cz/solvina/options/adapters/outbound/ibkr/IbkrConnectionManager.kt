package cz.solvina.options.adapters.outbound.ibkr

import cz.solvina.options.domain.features.connection.ConnectionPort
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.models.ConnectionStatus
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val connectionMutex = Mutex()
    private var connectionInitialized = false
    private var reconnectThread: Thread? = null

    suspend fun ensureConnected(): Boolean {
        connectionMutex.withLock {
            if (ibkrConnection.isConnected()) {
                return true
            }

            if (!connectionInitialized) {
                logger.info { "Initializing IBKR connection (lazy initialization)" }
                connectionInitialized = true
                return connectWithRetry()
            }

            return ibkrConnection.isConnected()
        }
    }

    private fun connectWithRetry(): Boolean {
        ibkrConnection.connect()

        if (!ibkrConnection.isConnected() && config.autoReconnect) {
            logger.warn { "Initial connection failed, starting auto-reconnect thread" }
            startAutoReconnectThread()
        }

        return ibkrConnection.isConnected()
    }

    private fun startAutoReconnectThread() {
        if (reconnectThread?.isAlive == true) {
            logger.debug { "Auto-reconnect thread already running" }
            return
        }

        reconnectThread =
            Thread {
                while (config.autoReconnect && !Thread.currentThread().isInterrupted) {
                    try {
                        if (!ibkrConnection.isConnected()) {
                            logger.info { "Attempting to reconnect to IBKR..." }
                            ibkrConnection.connect()
                        } else {
                            logger.debug { "IBKR connection is healthy" }
                            break
                        }

                        Thread.sleep(config.reconnectIntervalMs)
                    } catch (e: InterruptedException) {
                        logger.info { "Auto-reconnect thread interrupted" }
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        logger.error(e) { "Error in auto-reconnect thread" }
                    }
                }
            }.apply {
                name = "IBKR-Auto-Reconnect"
                isDaemon = true
                start()
            }
    }

    override suspend fun connect(): Boolean = ensureConnected()

    override fun disconnect() {
        reconnectThread?.interrupt()
        reconnectThread = null
        ibkrConnection.disconnect()
        connectionInitialized = false
        logger.info { "IBKR connection manager disconnected" }
    }

    override fun isConnected(): Boolean = ibkrConnection.isConnected()

    override fun getConnectionStatus(): ConnectionStatus =
        ConnectionStatus(
            connected = ibkrConnection.isConnected(),
            autoReconnectEnabled = config.autoReconnect,
            autoReconnectThreadActive = reconnectThread?.isAlive == true,
            connectionInitialized = connectionInitialized,
        )
}
