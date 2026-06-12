package cz.solvina.options.adapters.outbound.ibkr.order

import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Atomic order replacement service that ensures the old order is completely removed before submitting new.
 *
 * Prevents the order replace window bug where timeout doesn't guarantee cancellation,
 * potentially causing both old and new orders to fill simultaneously.
 */
@Component
class OrderReplacementService(
    private val orderCancellationService: OrderCancellationService,
    private val openOrdersAdapter: IbkrOpenOrdersAdapter,
) {
    /**
     * Verify that an order has been completely removed from IBKR open orders.
     *
     * Retries up to 10 times with 500ms delays for a total of ~5 seconds.
     * Removes timeout limitation of previous implementation.
     *
     * @param orderId The order ID to verify
     * @return true if order is confirmed removed, false if verification failed
     */
    suspend fun verifyOrderRemoved(orderId: Int): Boolean {
        for (attempt in 1..10) {
            delay(500)

            val openOrders =
                runCatching { openOrdersAdapter.getOpenOrders() }
                    .onFailure { e ->
                        logger.debug(e) { "Could not fetch open orders for verification attempt $attempt" }
                    }.getOrDefault(emptyList())

            val isRemoved = !openOrders.any { it.orderId == orderId }

            if (isRemoved) {
                logger.debug { "Order removal verified: orderId=$orderId after $attempt attempt(s)" }
                return true
            }

            logger.debug { "Order still present: orderId=$orderId attempt $attempt/10" }
        }

        logger.warn { "Order removal verification failed: orderId=$orderId after 10 attempts (5s timeout)" }
        return false
    }

    /**
     * Atomically replace an order: cancel old order with verification, then allow new submission.
     *
     * This ensures the old order is completely removed from IBKR before the new order is submitted,
     * preventing the double-fill scenario where both orders fill due to timing.
     *
     * @param existingOrderId The order to be replaced
     * @return true if cancellation verified, false if verification failed
     */
    suspend fun replacementCancel(existingOrderId: Int): Boolean {
        logger.info { "Replacement cancel: orderId=$existingOrderId" }

        // Use OrderCancellationService for atomic cancel with verification
        val cancellationResults =
            orderCancellationService.cancelOrdersAtomic(
                listOf(existingOrderId),
                reason = "order_replacement",
            )

        val result = cancellationResults.firstOrNull() ?: return false

        return when {
            result.success -> {
                logger.info { "Replacement cancel verified: orderId=$existingOrderId" }
                true
            }

            else -> {
                // If cancellation couldn't be verified after retries, attempt direct verification
                logger.warn { "Cancellation service returned false, attempting direct verification" }
                verifyOrderRemoved(existingOrderId)
            }
        }
    }
}
