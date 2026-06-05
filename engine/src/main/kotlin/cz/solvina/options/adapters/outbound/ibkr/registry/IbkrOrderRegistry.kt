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
    private val fillPrices = ConcurrentHashMap<Int, java.math.BigDecimal>()
    private val selfCancelledOrders = ConcurrentHashMap.newKeySet<Int>()
    private val orderIdCounter = AtomicInteger(1)

    fun seedOrderId(id: Int) {
        orderIdCounter.updateAndGet { current -> maxOf(current, id) }
        logger.info { "Order ID counter advanced to ${orderIdCounter.get()} (received $id)" }
    }

    fun nextOrderId(): Int = orderIdCounter.getAndIncrement()

    fun markSelfCancelled(orderId: Int) {
        selfCancelledOrders.add(orderId)
    }

    fun onOrderStatus(
        orderId: Int,
        status: String,
        avgFillPrice: Double = 0.0,
    ) {
        val deferred = pendingOrderStatus[orderId] ?: return
        when (status.lowercase()) {
            "filled" -> {
                if (avgFillPrice > 0.0) fillPrices[orderId] = java.math.BigDecimal(avgFillPrice).setScale(4, java.math.RoundingMode.HALF_UP)
                deferred.complete(OrderStatus.FILLED)
            }
            "cancelled", "inactive", "apicancelled", "rejected" -> deferred.complete(OrderStatus.CANCELLED)
            else -> logger.debug { "Order $orderId status: $status (waiting for terminal status)" }
        }
    }

    fun consumeFillPrice(orderId: Int): java.math.BigDecimal? = fillPrices.remove(orderId)

    fun onError(
        id: Int,
        code: Int,
        msg: String,
    ) {
        // 201 = order rejected, 202 = order cancelled — business outcomes, not technical failures.
        // Paper-account "guaranteed-to-lose" limit is expected and logged at WARN.
        // Any other 201 (permissions, risk limits, etc.) is unexpected and logged at ERROR.
        // 399 = order queued for next market open. Fail-fast so the chase exits immediately;
        // the caller (OrderChaseService) will cancel the queued order and not reprice.
        if (code == 399) {
            logger.warn { "Order $id queued for after-hours [code=399] — failing fast to avoid stale overnight fill" }
            pendingOrderStatus.remove(id)?.complete(OrderStatus.CANCELLED)
            return
        }
        if (code == 201 || code == 202) {
            val isPaperAccountLimit =
                msg.contains("Guaranteed-to-Lose", ignoreCase = true) ||
                    msg.contains("guaranteed-loss", ignoreCase = true)
            val isSelfCancelled = selfCancelledOrders.remove(id)
            when {
                isSelfCancelled -> logger.debug { "Order $id self-cancelled for repricing [code=$code]" }
                isPaperAccountLimit -> logger.warn { "Order $id rejected/cancelled [code=$code]: $msg" }
                else -> logger.error { "Order $id rejected [code=$code] — unexpected reason (check account permissions): $msg" }
            }
            pendingOrderStatus.remove(id)?.complete(OrderStatus.CANCELLED)
            return
        }
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
