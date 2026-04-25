package cz.solvina.options.adapters.outbound.ibkr

import com.ib.client.EClientSocket
import com.ib.client.EReader
import com.ib.client.EReaderSignal
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrConnection(
    private val config: IbkrConnectionConfig,
    private val eReaderSignal: EReaderSignal,
    private val client: EClientSocket,
) {
    private var readerThread: Thread? = null

    fun connect() {
        if (client.isConnected) {
            logger.debug { "Already connected to IBKR" }
            return
        }

        logger.info { "Connecting to IBKR with $config" }
        client.eConnect(config.host, config.port, config.clientId)
        if (client.isConnected) {
            logger.info { "Connected successfully to IBKR" }
            startMessageReader()
        } else {
            logger.error { "Failed to connect to IBKR" }
        }
    }

    private fun startMessageReader() {
        if (readerThread?.isAlive == true) {
            logger.debug { "Message reader thread already running" }
            return
        }

        val reader = EReader(client, eReaderSignal)
        reader.start()

        readerThread =
            Thread {
                while (client.isConnected) {
                    eReaderSignal.waitForSignal()
                    try {
                        reader.processMsgs()
                    } catch (e: Exception) {
                        logger.error(e) { "Error processing IBKR messages: ${e.message}" }
                    }
                }
                logger.info { "Message reader thread exiting" }
            }.apply {
                name = "IBKR-Message-Reader"
                isDaemon = true
                start()
            }
    }

    fun isConnected(): Boolean = client.isConnected

    fun disconnect() {
        if (client.isConnected) {
            client.eDisconnect()
            logger.info { "Disconnected from IBKR" }
        } else {
            logger.warn { "Not connected to IBKR" }
        }

        readerThread?.interrupt()
        readerThread = null
    }
}
