package cz.solvina.options.adapters.outbound.ibkr.order

import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/** Outcome of attempting to replace an order: absence from open-orders alone can mean either. */
sealed interface ReplacementCancelResult {
    /** Old order confirmed gone and NOT filled — safe to submit the replacement. */
    data object Removed : ReplacementCancelResult

    /** Old order was actually FILLED (raced the cancel) — do NOT submit a replacement. */
    data object Filled : ReplacementCancelResult

    /** Could not confirm either state within the verification window. */
    data object Unverified : ReplacementCancelResult
}

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
    private val orderRegistry: IbkrOrderRegistry,
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
     * Absence from the open-orders list proves the order is no longer WORKING, but that is true both
     * when it was cancelled AND when it FILLED in the race window — so the registry's fill-status
     * (set from the authoritative orderStatus callback) is consulted to tell them apart before the
     * caller decides whether to submit a replacement.
     *
     * @param existingOrderId The order to be replaced
     * @return [ReplacementCancelResult.Removed] if safe to replace, [ReplacementCancelResult.Filled]
     *   if the old order filled instead, [ReplacementCancelResult.Unverified] if neither could be confirmed.
     */
    suspend fun replacementCancel(existingOrderId: Int): ReplacementCancelResult {
        logger.info { "Replacement cancel: orderId=$existingOrderId" }

        // Use OrderCancellationService for atomic cancel with verification
        val cancellationResults =
            orderCancellationService.cancelOrdersAtomic(
                listOf(existingOrderId),
                reason = "order_replacement",
            )

        val result = cancellationResults.firstOrNull()

        val removedFromOpenOrders =
            when {
                result == null -> false
                result.success -> true
                else -> {
                    // If cancellation couldn't be verified after retries, attempt direct verification
                    logger.warn { "Cancellation service returned false, attempting direct verification" }
                    verifyOrderRemoved(existingOrderId)
                }
            }

        if (!removedFromOpenOrders) return ReplacementCancelResult.Unverified

        if (orderRegistry.isFilled(existingOrderId)) {
            logger.warn { "Replacement cancel: orderId=$existingOrderId was FILLED, not cancelled — no replacement will be submitted" }
            return ReplacementCancelResult.Filled
        }

        logger.info { "Replacement cancel verified: orderId=$existingOrderId removed (not filled)" }
        return ReplacementCancelResult.Removed
    }
}
