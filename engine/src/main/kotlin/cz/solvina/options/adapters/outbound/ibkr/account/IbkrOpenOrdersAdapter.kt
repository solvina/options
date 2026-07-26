package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrOpenOrdersAdapter(
    private val registry: IbkrOrdersRegistry,
    private val client: EClientSocket,
    private val config: IbkrConnectionConfig,
) {
    suspend fun requestOrderUpdates(): Boolean {
        if (config.clientId == 0) {
            client.reqOpenOrders()
            client.reqAutoOpenOrders(true)
        } else {
            logger.warn { "IBKR manual-order binding is disabled because clientId=${config.clientId}; use clientId=0 to cancel TWS/manual orders from the API" }
        }
        client.reqAllOpenOrders()
        logger.info { "Requested open orders" }
        return registry.awaitOpenOrders()
    }

    suspend fun getOpenOrders(): List<OpenOrder> = registry.getOpenOrders()
}
