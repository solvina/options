package cz.solvina.options.adapters.outbound.ibkr.order

import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val orderRegistry: IbkrOrderRegistry,
) {
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

        // Fast path: a fill can beat our cancel. If the broker already confirmed the fill (via the
        // authoritative orderStatus callback), the order is a real position — adopt it, submit no
        // replacement, and don't waste a cancel round-trip.
        if (orderRegistry.isFilled(existingOrderId)) {
            logger.warn { "Replacement cancel: orderId=$existingOrderId already FILLED — no replacement will be submitted" }
            return ReplacementCancelResult.Filled
        }

        // Atomic cancel with verification. OrderCancellationService now confirms removal from the
        // registry's push-based status signal first, only falling back to open-orders polling — so a
        // single verification layer here is enough (the previous second poll-based layer was redundant
        // and multiplied the reqAllOpenOrders load).
        val result =
            orderCancellationService
                .cancelOrdersAtomic(listOf(existingOrderId), reason = "order_replacement")
                .firstOrNull()

        if (result?.success != true) return ReplacementCancelResult.Unverified

        if (orderRegistry.isFilled(existingOrderId)) {
            logger.warn { "Replacement cancel: orderId=$existingOrderId was FILLED, not cancelled — no replacement will be submitted" }
            return ReplacementCancelResult.Filled
        }

        logger.info { "Replacement cancel verified: orderId=$existingOrderId removed (not filled)" }
        return ReplacementCancelResult.Removed
    }
}
