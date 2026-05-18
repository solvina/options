package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.Contract
import com.ib.client.Order
import com.ib.client.OrderState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.util.Collections

private val logger = KotlinLogging.logger {}

private val TERMINAL_STATUSES = setOf("cancelled", "filled", "apicancelled", "inactive")

@Component
class IbkrOpenOrdersRegistry {
    @Volatile private var pending: CompletableDeferred<List<OpenOrder>>? = null
    private val buffer: MutableList<OpenOrder> = Collections.synchronizedList(mutableListOf())

    fun startRequest(): CompletableDeferred<List<OpenOrder>> {
        pending?.cancel()
        buffer.clear()
        val deferred = CompletableDeferred<List<OpenOrder>>()
        pending = deferred
        return deferred
    }

    fun onOpenOrder(
        orderId: Int,
        contract: Contract,
        order: Order,
        orderState: OrderState,
    ) {
        val status = orderState.status()?.name ?: return
        if (status.lowercase() in TERMINAL_STATUSES) return
        buffer.add(
            OpenOrder(
                orderId = orderId,
                symbol = contract.symbol() ?: "",
                action = order.action()?.toString() ?: "",
                orderType = order.orderType()?.toString() ?: "",
                limitPrice = order.lmtPrice().takeIf { !it.isNaN() && it != Double.MAX_VALUE },
                status = status,
            ),
        )
    }

    fun onOpenOrderEnd() {
        val snapshot = buffer.sortedByDescending { it.orderId }
        logger.debug { "openOrderEnd: ${snapshot.size} open order(s)" }
        pending?.complete(snapshot)
        pending = null
    }

    fun cancelPending() {
        pending?.cancel()
        pending = null
        buffer.clear()
    }
}
