package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.EClientSocket
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Atomic order cancellation service that verifies cancellation by querying IBKR.
 *
 * Prevents the stale order bug where cancel requests are issued but not verified,
 * allowing orphaned orders to fill after operations complete (e.g., AMD bug).
 */
@Component
class OrderCancellationService(
    private val client: EClientSocket,
    private val openOrdersAdapter: IbkrOpenOrdersAdapter,
    private val registry: IbkrOrderRegistry,
) {
    data class CancellationResult(
        val orderId: Int,
        val success: Boolean,
        val reason: String,
        val attemptCount: Int,
    )

    /**
     * Atomically cancel orders by issuing cancel request and verifying removal.
     *
     * For each order:
     * 1. Check current state via openOrders query
     * 2. Skip if already filled
     * 3. Issue cancel request
     * 4. Verify removal via retry loop (5 × 200ms)
     * 5. Log result
     *
     * @param orderIds List of order IDs to cancel
     * @param reason Reason for cancellation (logged for audit trail)
     * @return List of cancellation results with success status per order
     */
    suspend fun cancelOrdersAtomic(
        orderIds: List<Int>,
        reason: String,
    ): List<CancellationResult> {
        if (orderIds.isEmpty()) return emptyList()

        logger.info { "Atomic cancel: $orderIds reason=$reason" }

        val results = mutableListOf<CancellationResult>()

        val openOrders =
            runCatching { openOrdersAdapter.getOpenOrders() }
                .onFailure { e -> logger.warn(e) { "Could not fetch open orders for cancellation verification — will cancel all" } }
                .getOrNull()

        for (orderId in orderIds) {
            val openOrder = openOrders?.find { it.orderId == orderId }

            when {
                openOrder?.status == "Filled" -> {
                    // Confirmed filled — skip cancellation
                    logger.info { "Cancel skipped: orderId=$orderId status=Filled" }
                    results.add(
                        CancellationResult(
                            orderId = orderId,
                            success = true,
                            reason = "already_filled_or_not_found",
                            attemptCount = 0,
                        ),
                    )
                }

                else -> {
                    // Order exists, not confirmed filled, or open-orders fetch failed — cancel and verify
                    logger.info {
                        "Cancel request: orderId=$orderId symbol=${openOrder?.symbol ?: "unknown"} action=${openOrder?.action ?: "unknown"}"
                    }
                    // This is our own cancel (repricing/cleanup), not a broker rejection — mark it so
                    // the resulting code-202 callback is logged at DEBUG and no reject reason is stashed.
                    registry.markSelfCancelled(orderId)
                    client.cancelOrder(orderId, OrderCancel())

                    val verificationResult = verifyOrderRemoved(orderId, maxRetries = 5)
                    results.add(verificationResult)

                    if (!verificationResult.success) {
                        logger.warn {
                            "Cancel verification failed: orderId=$orderId after ${verificationResult.attemptCount} attempts"
                        }
                    }
                }
            }
        }

        return results
    }

    /**
     * Verify that an order has been removed from IBKR's open orders list.
     *
     * Retries up to maxRetries times with 200ms delays to account for API propagation delay.
     */
    private suspend fun verifyOrderRemoved(
        orderId: Int,
        maxRetries: Int = 5,
    ): CancellationResult {
        for (attempt in 1..maxRetries) {
            delay(200)

            // Authoritative, push-based signal first: the orderStatus callback records terminal
            // filled/cancelled state in the registry. In the common case the cancel is confirmed here
            // within an attempt or two and we never fire a full-book reqAllOpenOrders — that request
            // storm (up to ~16 per failing cancel while laddering) was itself worsening the latency
            // that caused spurious "still present" verdicts.
            if (registry.isRemoved(orderId)) {
                logger.debug { "Cancel verified via status callback: orderId=$orderId after $attempt attempt(s)" }
                return CancellationResult(
                    orderId = orderId,
                    success = true,
                    reason = "verified_via_status_callback",
                    attemptCount = attempt,
                )
            }

            // Fallback: the callback can lag or be missed — confirm against the broker's open-orders list.
            val openOrders =
                runCatching { openOrdersAdapter.getOpenOrders() }
                    .onFailure { e -> logger.warn(e) { "getOpenOrders failed on attempt $attempt/$maxRetries — will retry" } }
                    .getOrNull()

            if (openOrders == null) continue

            val isRemoved = !openOrders.any { it.orderId == orderId }

            if (isRemoved) {
                logger.debug { "Cancel verified: orderId=$orderId removed after $attempt attempt(s)" }
                return CancellationResult(
                    orderId = orderId,
                    success = true,
                    reason = "verified_removed",
                    attemptCount = attempt,
                )
            }

            logger.debug { "Cancel not yet verified: orderId=$orderId attempt $attempt/$maxRetries" }
        }

        return CancellationResult(
            orderId = orderId,
            success = false,
            reason = "verification_timeout_after_5_attempts",
            attemptCount = maxRetries,
        )
    }
}
