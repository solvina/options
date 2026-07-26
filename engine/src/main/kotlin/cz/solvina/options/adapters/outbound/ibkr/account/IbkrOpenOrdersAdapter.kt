package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.EClientSocket
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrOpenOrdersAdapter(
    private val registry: IbkrOrdersRegistry,
    private val client: EClientSocket,
) {
    suspend fun requestOrderUpdates(): Boolean {
        client.reqAllOpenOrders()
        client.reqAutoOpenOrders(true)
        logger.info { "Requested open orders" }
        return registry.awaitOpenOrders()
    }

    suspend fun getOpenOrders(): List<OpenOrder> = registry.getOpenOrders()
}
