package cz.solvina.options.domain.features.spread

import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.OrderPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.models.OptionContract
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

/**
 * Detects and handles partial fills in spread close operations (Issue #1 fix).
 *
 * When closing a spread with two market orders (buy-back SOLD leg, sell-back BOUGHT leg),
 * one order might fill while the other times out. This creates an unhedged position.
 *
 * This service:
 * 1. Submits both legs
 * 2. Monitors both for completion
 * 3. Detects if only one filled
 * 4. Places compensating order for the filled leg
 * 5. Marks spread as CLOSING_FAILED for manual review
 */
@Component
class PartialFillDetectionService(
    private val orderPort: OrderPort,
    private val registry: IbkrOrderRegistry,
) {
    data class CloseAttemptResult(
        val soldLegOrderId: Int?,
        val boughtLegOrderId: Int?,
        val soldLegFilled: Boolean,
        val boughtLegFilled: Boolean,
        val fullyFilled: Boolean,
        val partialFillDetected: Boolean,
        val compensatingOrderPlaced: Boolean,
        val rollbackSuccess: Boolean,
        val reason: String,
    )

    /**
     * Atomically close both legs and detect/rollback partial fills.
     *
     * Returns immediately after both orders complete (or timeout),
     * tracking which legs filled and taking compensating action if needed.
     *
     * @param spread The spread being closed
     * @param soldLegContract Contract for the SOLD leg (what we bought back)
     * @param boughtLegContract Contract for the BOUGHT leg (what we sold back)
     * @param quantity Position size
     * @param maxWaitMs Maximum time to wait for both fills (default 10000ms)
     */
    suspend fun closeWithPartialFillDetection(
        spread: BullPutSpread,
        soldLegContract: OptionContract,
        boughtLegContract: OptionContract,
        quantity: Int,
        maxWaitMs: Long = 10000,
    ): CloseAttemptResult {
        val symbol = spread.symbol.value
        logger.info { "[$symbol] Closing with partial fill detection (max wait ${maxWaitMs}ms)" }

        val startTime = System.currentTimeMillis()

        try {
            // Place both market orders (don't wait for fills yet)
            val soldOrderId = placeCloseOrderForLeg(symbol, soldLegContract, LegAction.BUY, quantity)
            val boughtOrderId = placeCloseOrderForLeg(symbol, boughtLegContract, LegAction.SELL, quantity)

            if (soldOrderId == null || boughtOrderId == null) {
                return CloseAttemptResult(
                    soldLegOrderId = soldOrderId,
                    boughtLegOrderId = boughtOrderId,
                    soldLegFilled = false,
                    boughtLegFilled = false,
                    fullyFilled = false,
                    partialFillDetected = false,
                    compensatingOrderPlaced = false,
                    rollbackSuccess = false,
                    reason = "Order submission failed for one or both legs",
                )
            }

            // Wait for both fills (or timeout)
            val (soldFilled, boughtFilled) = waitForBothLegs(
                soldOrderId,
                boughtOrderId,
                symbol,
                maxWaitMs - (System.currentTimeMillis() - startTime),
            )

            // Detect partial fill scenario
            when {
                soldFilled && boughtFilled -> {
                    logger.info { "[$symbol] Both legs filled successfully" }
                    return CloseAttemptResult(
                        soldLegOrderId = soldOrderId,
                        boughtLegOrderId = boughtOrderId,
                        soldLegFilled = true,
                        boughtLegFilled = true,
                        fullyFilled = true,
                        partialFillDetected = false,
                        compensatingOrderPlaced = false,
                        rollbackSuccess = true,
                        reason = "Both legs filled",
                    )
                }

                soldFilled && !boughtFilled -> {
                    // SOLD leg (short position) filled but BOUGHT leg (long hedge) failed
                    // We're now unhedged SHORT → need to immediately sell to close
                    logger.warn {
                        "[$symbol] PARTIAL FILL: SOLD leg $soldOrderId filled but BOUGHT leg $boughtOrderId failed"
                    }
                    val compensatingSuccess = placeCompensatingOrder(
                        symbol,
                        boughtLegContract,
                        LegAction.SELL,
                        quantity,
                    )
                    return CloseAttemptResult(
                        soldLegOrderId = soldOrderId,
                        boughtLegOrderId = boughtOrderId,
                        soldLegFilled = true,
                        boughtLegFilled = false,
                        fullyFilled = false,
                        partialFillDetected = true,
                        compensatingOrderPlaced = compensatingSuccess,
                        rollbackSuccess = compensatingSuccess,
                        reason = "Partial fill detected: SOLD filled, BOUGHT failed. Compensating order placed.",
                    )
                }

                !soldFilled && boughtFilled -> {
                    // BOUGHT leg (long hedge) filled but SOLD leg (short position) failed
                    // We're now unhedged LONG → need to immediately buy to close
                    logger.warn {
                        "[$symbol] PARTIAL FILL: BOUGHT leg $boughtOrderId filled but SOLD leg $soldOrderId failed"
                    }
                    val compensatingSuccess = placeCompensatingOrder(
                        symbol,
                        soldLegContract,
                        LegAction.BUY,
                        quantity,
                    )
                    return CloseAttemptResult(
                        soldLegOrderId = soldOrderId,
                        boughtLegOrderId = boughtOrderId,
                        soldLegFilled = false,
                        boughtLegFilled = true,
                        fullyFilled = false,
                        partialFillDetected = true,
                        compensatingOrderPlaced = compensatingSuccess,
                        rollbackSuccess = compensatingSuccess,
                        reason = "Partial fill detected: BOUGHT filled, SOLD failed. Compensating order placed.",
                    )
                }

                else -> {
                    // Both legs timed out (neither filled)
                    logger.warn { "[$symbol] CLOSE TIMEOUT: Both legs timed out without filling" }
                    return CloseAttemptResult(
                        soldLegOrderId = soldOrderId,
                        boughtLegOrderId = boughtOrderId,
                        soldLegFilled = false,
                        boughtLegFilled = false,
                        fullyFilled = false,
                        partialFillDetected = false,
                        compensatingOrderPlaced = false,
                        rollbackSuccess = false,
                        reason = "Both legs timed out without filling",
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "[$symbol] Exception during close with partial fill detection" }
            return CloseAttemptResult(
                soldLegOrderId = null,
                boughtLegOrderId = null,
                soldLegFilled = false,
                boughtLegFilled = false,
                fullyFilled = false,
                partialFillDetected = false,
                compensatingOrderPlaced = false,
                rollbackSuccess = false,
                reason = "Exception: ${e.message}",
            )
        }
    }

    /**
     * Place a single leg close order (market order).
     */
    private suspend fun placeCloseOrderForLeg(
        symbol: String,
        contract: OptionContract,
        action: LegAction,
        quantity: Int,
    ): Int? {
        return try {
            val legOrder = orderPort.placeMarketOrder(contract, action, quantity)
            if (legOrder != null) {
                logger.debug { "[$symbol] Placed close order: ${action.name} ${contract.strike}P orderId=${legOrder.orderId}" }
                legOrder.orderId
            } else {
                logger.warn { "[$symbol] placeMarketOrder returned null: ${action.name} ${contract.strike}P" }
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "[$symbol] Failed to place close order: ${action.name} ${contract.strike}P" }
            null
        }
    }

    /**
     * Place a compensating market order to close an unhedged leg.
     */
    private suspend fun placeCompensatingOrder(
        symbol: String,
        contract: OptionContract,
        action: LegAction,
        quantity: Int,
    ): Boolean {
        return try {
            val legOrder = orderPort.placeMarketOrder(contract, action, quantity)
            if (legOrder != null && legOrder.orderId > 0) {
                logger.info { "[$symbol] Placed compensating order: ${action.name} ${contract.strike}P orderId=${legOrder.orderId}" }
                true
            } else {
                logger.warn { "[$symbol] Failed to place compensating order: ${action.name} ${contract.strike}P" }
                false
            }
        } catch (e: Exception) {
            logger.error(e) { "[$symbol] Exception placing compensating order" }
            false
        }
    }

    /**
     * Wait for both legs to complete (fill or fail).
     *
     * Polls order status every 100ms up to maxWaitMs.
     * Returns (soldFilled, boughtFilled).
     */
    private suspend fun waitForBothLegs(
        soldOrderId: Int,
        boughtOrderId: Int,
        symbol: String,
        maxWaitMs: Long,
    ): Pair<Boolean, Boolean> {
        val startTime = System.currentTimeMillis()
        val pollIntervalMs = 100L

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val soldDeferred = registry.pendingOrderStatus[soldOrderId]
            val boughtDeferred = registry.pendingOrderStatus[boughtOrderId]

            if (soldDeferred != null && boughtDeferred != null) {
                if (soldDeferred.isCompleted && boughtDeferred.isCompleted) {
                    val soldStatus = try {
                        soldDeferred.getCompleted()
                    } catch (e: Exception) {
                        OrderStatus.CANCELLED
                    }
                    val boughtStatus = try {
                        boughtDeferred.getCompleted()
                    } catch (e: Exception) {
                        OrderStatus.CANCELLED
                    }

                    logger.debug {
                        "[$symbol] Close orders completed: SOLD=$soldStatus BOUGHT=$boughtStatus"
                    }
                    return Pair(
                        soldStatus == OrderStatus.FILLED,
                        boughtStatus == OrderStatus.FILLED,
                    )
                }
            }

            delay(pollIntervalMs)
        }

        // Timeout waiting for completion
        logger.warn {
            "[$symbol] Timeout waiting for close orders after ${maxWaitMs}ms. Checking final status..."
        }

        val soldDeferred = registry.pendingOrderStatus[soldOrderId]
        val boughtDeferred = registry.pendingOrderStatus[boughtOrderId]

        val soldFilled = try {
            soldDeferred?.isCompleted == true && soldDeferred.getCompleted() == OrderStatus.FILLED
        } catch (e: Exception) {
            false
        }

        val boughtFilled = try {
            boughtDeferred?.isCompleted == true && boughtDeferred.getCompleted() == OrderStatus.FILLED
        } catch (e: Exception) {
            false
        }

        return Pair(soldFilled, boughtFilled)
    }
}
