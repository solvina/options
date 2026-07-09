package cz.solvina.options.adapters.outbound.ibkr.registry

import cz.solvina.options.domain.features.order.OrderStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

private val TERMINAL_CANCEL_STATUSES = setOf("cancelled", "inactive", "apicancelled", "rejected")

@Component
class IbkrOrderRegistry {
    internal val pendingOrderStatus = ConcurrentHashMap<Int, CompletableDeferred<OrderStatus>>()
    private val fillPrices = ConcurrentHashMap<Int, java.math.BigDecimal>()
    private val selfCancelledOrders = ConcurrentHashMap.newKeySet<Int>()

    // Broker-reported rejection reason per orderId (IBKR code + message), so the execution loop can
    // attach the *why* to an ORDER_REJECTED outcome. onError delivers it on a different thread from the
    // status flow the executor consumes, so we stash it here for the executor to consume one-shot.
    private val rejectReasons = ConcurrentHashMap<Int, String>()

    // Orders confirmed FILLED by IBKR, kept independent of pendingOrderStatus (which is removed once
    // consumed). Absence from the open-orders list alone can mean "cancelled" OR "filled" — this set
    // lets callers (e.g. OrderReplacementService) tell the two apart before submitting a replacement.
    private val filledOrders = ConcurrentHashMap.newKeySet<Int>()

    // Orders IBKR has reported in a terminal CANCELLED state (cancelled/inactive/apicancelled/rejected).
    // This is the authoritative, push-based "no longer working" signal — verification paths consult it
    // instead of polling reqAllOpenOrders, which is expensive (returns the whole book) and laggy.
    private val cancelledOrders = ConcurrentHashMap.newKeySet<Int>()
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
        filled: java.math.BigDecimal = java.math.BigDecimal.ZERO,
        remaining: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    ) {
        // Record terminal state BEFORE the deferred lookup: a late "filled"/"cancelled" callback
        // arriving after the deferred was consumed/removed (e.g. a fill racing a cancel) must still be
        // visible via isFilled/isCancelled/consumeFillPrice — that is what lets cancel paths tell
        // "cancelled" from "filled" without polling the broker's open-orders list.
        val lower = status.lowercase()
        if (lower == "filled") {
            filledOrders.add(orderId)
            if (avgFillPrice > 0.0) fillPrices[orderId] = java.math.BigDecimal(avgFillPrice).setScale(4, java.math.RoundingMode.HALF_UP)
        }
        if (lower in TERMINAL_CANCEL_STATUSES) {
            cancelledOrders.add(orderId)
        }
        val deferred = pendingOrderStatus[orderId] ?: return
        // Qty is always 1 today, so no code path currently expects a partial fill. This is a tripwire
        // for when that stops being true: a terminal status with 0 < filled < total would otherwise
        // pass through silently as fully filled or fully cancelled.
        if (filled > java.math.BigDecimal.ZERO && remaining > java.math.BigDecimal.ZERO) {
            logger.warn {
                "Order $orderId reported terminal status '$status' with a PARTIAL quantity " +
                    "(filled=$filled remaining=$remaining) — partial fills are not modeled; treating per the terminal status"
            }
        }
        when (lower) {
            "filled" -> deferred.complete(OrderStatus.FILLED)
            in TERMINAL_CANCEL_STATUSES -> deferred.complete(OrderStatus.CANCELLED)
            else -> logger.debug { "Order $orderId status: $status (waiting for terminal status)" }
        }
    }

    fun consumeFillPrice(orderId: Int): java.math.BigDecimal? = fillPrices.remove(orderId)

    /** True while a fill watcher is armed for [orderId] (deferred registered and not yet consumed). */
    fun hasActiveWatch(orderId: Int): Boolean = pendingOrderStatus.containsKey(orderId)

    /**
     * Arms a fill watch for an order restored from persistence (placed by a previous engine run,
     * still working at the broker). Idempotent — an already-armed watch is left untouched.
     */
    fun ensureWatch(orderId: Int) {
        pendingOrderStatus.putIfAbsent(orderId, CompletableDeferred())
    }

    /** One-shot: the broker-reported rejection reason for [orderId] (IBKR code + message), or null. */
    fun consumeRejectReason(orderId: Int): String? = rejectReasons.remove(orderId)

    /** True once IBKR has confirmed this order FILLED — distinguishes "filled" from "cancelled/absent". */
    fun isFilled(orderId: Int): Boolean = filledOrders.contains(orderId)

    /** True once IBKR has confirmed this order terminally CANCELLED (cancelled/inactive/apicancelled/rejected). */
    fun isCancelled(orderId: Int): Boolean = cancelledOrders.contains(orderId)

    /**
     * True if WE initiated the cancel of this order (reprice or abort), as opposed to the broker
     * rejecting it. Lets the execution loop avoid misreading its own cancel as an ORDER_REJECTED.
     */
    fun wasSelfCancelled(orderId: Int): Boolean = selfCancelledOrders.contains(orderId)

    /**
     * True once IBKR has confirmed this order is no longer working — either FILLED or CANCELLED. This is
     * the authoritative, push-based replacement for polling the open-orders list to confirm removal.
     */
    fun isRemoved(orderId: Int): Boolean = isFilled(orderId) || isCancelled(orderId)

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
            rejectReasons[id] = "code=$code: $msg"
            cancelledOrders.add(id)
            pendingOrderStatus.remove(id)?.complete(OrderStatus.CANCELLED)
            return
        }
        if (code == 201 || code == 202) {
            val isPaperAccountLimit =
                msg.contains("Guaranteed-to-Lose", ignoreCase = true) ||
                    msg.contains("guaranteed-loss", ignoreCase = true)
            // Consult, don't consume: the flag must stay queryable via wasSelfCancelled() so the
            // execution loop can tell a terminal CANCELLED that WE caused (reprice/abort) from a
            // genuine broker rejection. Order ids are monotonic within a session, so this never
            // masks a later real reject on a reused id.
            val isSelfCancelled = selfCancelledOrders.contains(id)
            when {
                isSelfCancelled -> logger.debug { "Order $id self-cancelled for repricing [code=$code]" }
                isPaperAccountLimit -> logger.warn { "Order $id rejected/cancelled [code=$code]: $msg" }
                else -> logger.error { "Order $id rejected [code=$code] — unexpected reason (check account permissions): $msg" }
            }
            // Self-cancel is our own reprice, not a broker rejection — don't surface it as a reason.
            if (!isSelfCancelled) rejectReasons[id] = "code=$code: $msg"
            cancelledOrders.add(id)
            pendingOrderStatus.remove(id)?.complete(OrderStatus.CANCELLED)
            return
        }
        rejectReasons[id] = "code=$code: $msg"
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
