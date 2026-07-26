package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.Order
import com.ib.client.OrderState
import cz.solvina.options.domain.features.order.BrokerOrderUpdate
import cz.solvina.options.domain.features.order.OrderStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

private val TERMINAL_STATUSES = setOf("cancelled", "filled", "apicancelled", "inactive", "rejected")
private val TERMINAL_CANCEL_STATUSES = setOf("cancelled", "inactive", "apicancelled", "rejected")

/**
 * Updated from TWS on every order status update, the preferred way to track orders.
 */
@Component
class IbkrOrdersRegistry {
    private val openOrders = ConcurrentHashMap<Int, OpenOrder>()
    private val fillPrices = ConcurrentHashMap<Int, BigDecimal>()
    private val selfCancelledOrders = ConcurrentHashMap.newKeySet<Int>()
    private val rejectReasons = ConcurrentHashMap<Int, String>()
    private val filledOrders = ConcurrentHashMap.newKeySet<Int>()
    private val cancelledOrders = ConcurrentHashMap.newKeySet<Int>()
    private val latestUpdates = ConcurrentHashMap<Int, BrokerOrderUpdate>()
    private val _updates = MutableSharedFlow<BrokerOrderUpdate>(extraBufferCapacity = 1_024)
    val updates = _updates.asSharedFlow()

    fun getAllOrders(): List<OpenOrder> = openOrders.values.toList()

    fun getOpenOrders(): List<OpenOrder> = openOrders.values.filter { it.status.lowercase() !in TERMINAL_STATUSES }

    fun current(orderId: Int): BrokerOrderUpdate? = latestUpdates[orderId]

    fun markSelfCancelled(orderId: Int) {
        selfCancelledOrders.add(orderId)
    }

    fun onOpenOrder(
        orderId: Int,
        contract: Contract,
        order: Order,
        orderState: OrderState,
    ) {
        val previous = openOrders[orderId]
        openOrders[orderId] =
            OpenOrder(
                orderId = orderId,
                symbol = contract.symbol() ?: "",
                action = order.action()?.toString() ?: "",
                orderType = order.orderType()?.toString() ?: "",
                limitPrice = order.lmtPrice().takeIf { !it.isNaN() && it != Double.MAX_VALUE },
                status = previous?.status?.takeIf { it.isNotBlank() } ?: orderState.status()?.name ?: "",
                filled = previous?.filled ?: Decimal.ZERO,
                remaining = previous?.remaining ?: Decimal.ZERO,
                avgFillPrice = previous?.avgFillPrice ?: 0.0,
                permId = previous?.permId ?: 0,
                parentId = previous?.parentId ?: 0,
                lastFillPrice = previous?.lastFillPrice ?: 0.0,
                whyHeld = previous?.whyHeld,
                mktCapPrice = previous?.mktCapPrice ?: 0.0,
            )
    }

    fun onOrderStatus(
        orderId: Int,
        status: String,
        filled: Decimal,
        remaining: Decimal,
        avgFillPrice: Double,
        permId: Int,
        parentId: Int,
        lastFillPrice: Double,
        clientId: Int,
        whyHeld: String?,
        mktCapPrice: Double,
    ) {
        val order =
            openOrders[orderId]
                ?: OpenOrder(
                    orderId = orderId,
                    symbol = "",
                    action = "",
                    orderType = "",
                    limitPrice = null,
                    status = "",
                )
        openOrders[orderId] =
            order.copy(
                status = status,
                filled = filled,
                remaining = remaining,
                avgFillPrice = avgFillPrice,
                permId = permId,
                parentId = parentId,
                lastFillPrice = lastFillPrice,
                whyHeld = whyHeld,
                mktCapPrice = mktCapPrice,
            )
        recordStatus(orderId, status, avgFillPrice, filled.value(), remaining.value(), rejectionReason = null)
    }

    fun onError(
        id: Int,
        code: Int,
        msg: String,
    ) {
        if (code == 399) {
            logger.warn { "Order $id queued for after-hours [code=399] — failing fast to avoid stale overnight fill" }
            val reason = "code=$code: $msg"
            rejectReasons[id] = reason
            cancelledOrders.add(id)
            publishError(id, "Cancelled", reason)
            return
        }
        if (code == 103) {
            logger.warn { "Order $id amend rejected [code=103 duplicate order id] — order still working, amend dropped" }
            return
        }
        if (code == 201 || code == 202) {
            val isPaperAccountLimit =
                msg.contains("Guaranteed-to-Lose", ignoreCase = true) ||
                    msg.contains("guaranteed-loss", ignoreCase = true)
            val isSelfCancelled = selfCancelledOrders.contains(id)
            when {
                isSelfCancelled -> logger.debug { "Order $id self-cancelled for repricing [code=$code]" }
                isPaperAccountLimit -> logger.warn { "Order $id rejected/cancelled [code=$code]: $msg" }
                else -> logger.error { "Order $id rejected [code=$code] — unexpected reason (check account permissions): $msg" }
            }
            val reason = if (isSelfCancelled) null else "code=$code: $msg"
            if (reason != null) rejectReasons[id] = reason
            cancelledOrders.add(id)
            publishError(id, "Cancelled", reason)
            return
        }
        val reason = "code=$code: $msg"
        rejectReasons[id] = reason
        cancelledOrders.add(id)
        publishError(id, "Rejected", reason)
    }

    suspend fun awaitTerminal(
        orderId: Int,
        timeout: Duration,
    ): OrderStatus {
        terminalStatus(orderId)?.let { return it }
        return try {
            withTimeout(timeout) {
                updates
                    .first { it.orderId == orderId && it.status.lowercase() in TERMINAL_STATUSES }
                    .toOrderStatus()
            }
        } catch (_: TimeoutCancellationException) {
            OrderStatus.PENDING
        }
    }

    suspend fun awaitTerminal(orderId: Int): OrderStatus {
        terminalStatus(orderId)?.let { return it }
        return updates
            .first { it.orderId == orderId && it.status.lowercase() in TERMINAL_STATUSES }
            .toOrderStatus()
    }

    fun consumeFillPrice(orderId: Int): BigDecimal? = fillPrices.remove(orderId)

    fun consumeRejectReason(orderId: Int): String? = rejectReasons.remove(orderId)

    fun isFilled(orderId: Int): Boolean = filledOrders.contains(orderId)

    fun isCancelled(orderId: Int): Boolean = cancelledOrders.contains(orderId)

    fun wasSelfCancelled(orderId: Int): Boolean = selfCancelledOrders.contains(orderId)

    fun isRemoved(orderId: Int): Boolean = isFilled(orderId) || isCancelled(orderId)

    private fun recordStatus(
        orderId: Int,
        status: String,
        avgFillPrice: Double,
        filled: BigDecimal,
        remaining: BigDecimal,
        rejectionReason: String?,
    ) {
        val lower = status.lowercase()
        val update =
            BrokerOrderUpdate(
                orderId = orderId,
                status = status,
                filled = filled,
                remaining = remaining,
                averageFillPrice = avgFillPrice.takeIf { it > 0.0 }?.let { BigDecimal(it).setScale(4, RoundingMode.HALF_UP) },
                rejectionReason = rejectionReason,
                receivedAt = Instant.now(),
            )
        latestUpdates[orderId] = update
        _updates.tryEmit(update)
        if (lower == "filled") {
            filledOrders.add(orderId)
            if (avgFillPrice > 0.0) fillPrices[orderId] = BigDecimal(avgFillPrice).setScale(4, RoundingMode.HALF_UP)
        }
        if (lower in TERMINAL_CANCEL_STATUSES) {
            cancelledOrders.add(orderId)
        }
        if (filled > BigDecimal.ZERO && remaining > BigDecimal.ZERO && lower in TERMINAL_STATUSES) {
            logger.warn {
                "Order $orderId reported terminal status '$status' with a PARTIAL quantity " +
                    "(filled=$filled remaining=$remaining) — partial fills are not modeled; treating per the terminal status"
            }
        }
    }

    private fun publishError(
        orderId: Int,
        status: String,
        reason: String?,
    ) {
        val previous = latestUpdates[orderId]
        recordStatus(
            orderId = orderId,
            status = status,
            avgFillPrice = previous?.averageFillPrice?.toDouble() ?: 0.0,
            filled = previous?.filled ?: BigDecimal.ZERO,
            remaining = previous?.remaining ?: BigDecimal.ZERO,
            rejectionReason = reason,
        )
        val order = openOrders[orderId]
        if (order != null) openOrders[orderId] = order.copy(status = status)
    }

    private fun terminalStatus(orderId: Int): OrderStatus? {
        if (isFilled(orderId)) return OrderStatus.FILLED
        if (isCancelled(orderId)) return OrderStatus.CANCELLED
        val update = latestUpdates[orderId] ?: return null
        return update.takeIf { it.status.lowercase() in TERMINAL_STATUSES }?.toOrderStatus()
    }

    private fun BrokerOrderUpdate.toOrderStatus(): OrderStatus =
        if (status.equals("Filled", ignoreCase = true)) OrderStatus.FILLED else OrderStatus.CANCELLED
}
