package cz.solvina.options.adapters.outbound.ibkr

import com.ib.client.EClientSocket
import com.ib.client.EReader
import com.ib.client.EReaderSignal
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrConnection(
    private val config: IbkrConnectionConfig,
    private val eReaderSignal: EReaderSignal,
    private val client: EClientSocket,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null

    fun connect() {
        if (client.isConnected) {
            logger.debug { "Already connected to IBKR" }
            return
        }

        logger.info { "Connecting to IBKR with $config" }
        client.eConnect(config.host, config.port, config.clientId)
        if (client.isConnected) {
            logger.info { "Connected successfully to IBKR" }
            if (config.paperAccount && !config.useLiveMarketData) {
                client.reqMarketDataType(3)
                logger.info { "Paper account: requesting delayed market data (type 3)" }
            } else {
                client.reqMarketDataType(1)
                logger.info {
                    "Requesting real-time market data (type 1)${if (config.paperAccount) " — paper account with live subscription" else ""}"
                }
            }
            startMessageReader()
        } else {
            logger.error { "Failed to connect to IBKR" }
        }
    }

    private fun startMessageReader() {
        if (readerJob?.isActive == true) {
            logger.debug { "Message reader job already running" }
            return
        }

        val reader = EReader(client, eReaderSignal)
        reader.start()

        readerJob =
            scope.launch {
                try {
                    while (isActive && client.isConnected) {
                        eReaderSignal.waitForSignal()
                        if (!isActive || !client.isConnected) {
                            break
                        }

                        try {
                            reader.processMsgs()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.error(e) { "Error processing IBKR messages: ${e.message}" }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } finally {
                    logger.info { "Message reader job exiting" }
                }
            }
    }

    fun isConnected(): Boolean = client.isConnected

    fun disconnect() {
        readerJob?.cancel()
        eReaderSignal.issueSignal()
        readerJob = null

        if (client.isConnected) {
            client.eDisconnect()
            logger.info { "Disconnected from IBKR" }
        } else {
            logger.warn { "Not connected to IBKR" }
        }
    }
}
