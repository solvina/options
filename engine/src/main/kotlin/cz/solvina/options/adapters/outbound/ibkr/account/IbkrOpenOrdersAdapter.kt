package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.EClientSocket
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrOpenOrdersAdapter(
    private val client: EClientSocket,
    private val registry: IbkrOpenOrdersRegistry,
) {
    suspend fun getOpenOrders(): List<OpenOrder> {
        val deferred = registry.startRequest()
        client.reqAllOpenOrders()
        logger.debug { "reqAllOpenOrders sent" }
        return withTimeout(10_000L) { deferred.await() }
    }
}
