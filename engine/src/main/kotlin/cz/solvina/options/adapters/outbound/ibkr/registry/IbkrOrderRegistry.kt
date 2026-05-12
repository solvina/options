package cz.solvina.options.adapters.outbound.ibkr.registry

import cz.solvina.options.domain.features.order.OrderStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

@Component
class IbkrOrderRegistry {
    internal val pendingOrderStatus = ConcurrentHashMap<Int, CompletableDeferred<OrderStatus>>()
    private val orderIdCounter = AtomicInteger(1)

    fun seedOrderId(id: Int) {
        orderIdCounter.updateAndGet { current -> maxOf(current, id) }
        logger.info { "Order ID counter advanced to ${orderIdCounter.get()} (received $id)" }
    }

    fun nextOrderId(): Int = orderIdCounter.getAndIncrement()

    fun onOrderStatus(
        orderId: Int,
        status: String,
    ) {
        val deferred = pendingOrderStatus[orderId] ?: return
        when (status.lowercase()) {
            "filled" -> deferred.complete(OrderStatus.FILLED)
            "cancelled", "inactive", "apicancelled", "rejected" -> deferred.complete(OrderStatus.CANCELLED)
            else -> logger.debug { "Order $orderId status: $status (waiting for terminal status)" }
        }
    }

    fun onError(
        id: Int,
        code: Int,
        msg: String,
    ) {
        pendingOrderStatus.remove(id)?.completeExceptionally(RuntimeException("IBKR error [code=$code]: $msg"))
    }

    fun cancelAllPending(cause: Exception) {
        if (pendingOrderStatus.isNotEmpty()) {
            logger.warn { "Cancelling ${pendingOrderStatus.size} pending order requests due to disconnect" }
        }
        pendingOrderStatus.values.forEach { it.completeExceptionally(cause) }
        pendingOrderStatus.clear()
    }
}
