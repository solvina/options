package cz.solvina.options.domain.features.order

import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Cleanup service for orphaned orders from failed verification attempts (Issue #8 fix).
 *
 * When position reconciliation times out during leg-by-leg order execution,
 * pending order deferreds can remain in the registry indefinitely. This service
 * ensures those orders are cancelled and cleaned up to prevent orphaned fills.
 */
@Component
class OrderCleanupService(
    private val orderPort: OrderPort,
    private val registry: IbkrOrderRegistry,
) {
    data class CleanupResult(
        val orderIds: List<Int>,
        val cancelledCount: Int,
        val failedCount: Int,
        val reason: String,
    )

    /**
     * Cleanup pending orders from a failed verification attempt.
     *
     * For each order ID that was tracked in this verification:
     * 1. Send cancel request to IBKR
     * 2. Remove deferred from registry
     * 3. Track success/failure
     *
     * @param orderIds List of order IDs to cleanup
     * @param reason Why these orders are being cleaned up
     * @return CleanupResult with count of cancelled vs failed
     */
    suspend fun cleanupPendingOrders(
        orderIds: List<Int>,
        reason: String,
    ): CleanupResult {
        if (orderIds.isEmpty()) {
            logger.debug { "Cleanup request with no orders (reason=$reason)" }
            return CleanupResult(
                orderIds = emptyList(),
                cancelledCount = 0,
                failedCount = 0,
                reason = "no_orders_to_cleanup",
            )
        }

        logger.warn {
            "Cleanup: Cancelling ${orderIds.size} orders due to $reason: $orderIds"
        }

        var cancelledCount = 0
        var failedCount = 0

        for (orderId in orderIds) {
            try {
                // Send cancel request to IBKR
                orderPort.cancelOrder(orderId)

                // Remove from registry to stop waiting for fills
                registry.pendingOrderStatus.remove(orderId)

                logger.info { "Cleanup: Cancelled orderId=$orderId (reason=$reason)" }
                cancelledCount++
            } catch (e: Exception) {
                logger.warn(e) {
                    "Cleanup: Failed to cancel orderId=$orderId (reason=$reason): ${e.message}"
                }
                failedCount++
            }
        }

        // Wait briefly for cancellation acknowledgement from IBKR
        delay(200)

        logger.info {
            "Cleanup complete: cancelled=$cancelledCount, failed=$failedCount " +
                "out of ${orderIds.size} orders (reason=$reason)"
        }

        return CleanupResult(
            orderIds = orderIds,
            cancelledCount = cancelledCount,
            failedCount = failedCount,
            reason = reason,
        )
    }

    /**
     * Cleanup on reconciliation timeout.
     *
     * Called when position verification times out. Ensures orders from
     * the failed verification are cancelled and removed from tracking.
     */
    suspend fun cleanupOnReconciliationTimeout(
        shortOrderId: Int?,
        longOrderId: Int?,
    ): CleanupResult {
        val orderIds = listOfNotNull(shortOrderId, longOrderId)
        return cleanupPendingOrders(orderIds, "reconciliation_timeout")
    }

    /**
     * Cleanup on verification cancellation.
     *
     * Called when verification is cancelled (e.g., user stops waiting).
     */
    suspend fun cleanupOnVerificationCancelled(orderIds: List<Int>): CleanupResult =
        cleanupPendingOrders(orderIds, "verification_cancelled")
}
