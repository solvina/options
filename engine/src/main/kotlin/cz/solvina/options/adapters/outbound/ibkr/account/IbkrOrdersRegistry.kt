package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.Order
import com.ib.client.OrderState
import cz.solvina.options.domain.features.order.BrokerOrderUpdate
import cz.solvina.options.domain.features.order.OrderStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Updated from TWS on every order status update, the preferred way to track orders.
 */
@Component
class IbkrOrdersRegistry {
    @Volatile
    private var openOrdersSynchronized = CompletableDeferred<Boolean>()
    private val openOrders = ConcurrentHashMap<Int, OpenOrder>()
    private val fillPrices = ConcurrentHashMap<Int, BigDecimal>()
    private val selfCancelledOrders = ConcurrentHashMap.newKeySet<Int>()
    private val rejectReasons = ConcurrentHashMap<Int, String>()
    private val filledOrders = ConcurrentHashMap.newKeySet<Int>()
    private val cancelledOrders = ConcurrentHashMap.newKeySet<Int>()
    private val latestUpdates = ConcurrentHashMap<Int, BrokerOrderUpdate>()
    private val terminalWaiters = ConcurrentHashMap<Int, CompletableDeferred<OrderStatus>>()
    private val _updates = MutableSharedFlow<BrokerOrderUpdate>(extraBufferCapacity = 1_024)
    val updates = _updates.asSharedFlow()

    fun getAllOrders(): List<OpenOrder> = openOrders.values.toList()

    fun getOpenOrders(): List<OpenOrder> = openOrders.values.filter { !OrderStatus.fromBrokerStatus(it.status).isTerminal }

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
        val permId = order.permId().takeIf { it > 0 } ?: previous?.permId ?: 0
        val clientId = order.clientId().takeIf { it >= 0 } ?: previous?.clientId
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
                permId = permId,
                clientId = clientId,
                parentId = previous?.parentId ?: 0,
                lastFillPrice = previous?.lastFillPrice ?: 0.0,
                whyHeld = previous?.whyHeld,
                mktCapPrice = previous?.mktCapPrice ?: 0.0,
            )
    }

    fun onOrderBound(
        permId: Long,
        apiClientId: Int,
        apiOrderId: Int,
    ) {
        if (apiOrderId == 0) return
        val matched =
            openOrders.entries
                .filter { (_, order) -> order.permId.toLong() == permId && order.orderId != apiOrderId }
                .toList()
        matched.forEach { (oldOrderId, order) ->
            if (openOrders.remove(oldOrderId, order)) {
                openOrders[apiOrderId] = order.copy(orderId = apiOrderId, clientId = apiClientId)
                latestUpdates.remove(oldOrderId)?.let { latestUpdates[apiOrderId] = it.copy(orderId = apiOrderId) }
                logger.info {
                    "Bound IBKR order permId=$permId from orderId=$oldOrderId to apiOrderId=$apiOrderId (apiClientId=$apiClientId)"
                }
            }
        }
    }

    fun onOpenOrderEnd() {
        openOrdersSynchronized.complete(true)
    }

    /** A reconnect must re-sync: drop the completed signal so awaitOpenOrders waits for the next end. */
    fun onDisconnect() {
        openOrdersSynchronized = CompletableDeferred()
    }

    suspend fun awaitOpenOrders() = openOrdersSynchronized.await()

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
                clientId = clientId.takeIf { it >= 0 } ?: order.clientId,
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
            withTimeout(timeout) { waiterFor(orderId).await() }
        } catch (_: TimeoutCancellationException) {
            OrderStatus.PENDING
        }
    }

    suspend fun awaitTerminal(orderId: Int): OrderStatus {
        terminalStatus(orderId)?.let { return it }
        return waiterFor(orderId).await()
    }

    fun consumeFillPrice(orderId: Int): BigDecimal? = fillPrices.remove(orderId)

    fun consumeRejectReason(orderId: Int): String? = rejectReasons.remove(orderId)

    fun isFilled(orderId: Int): Boolean = filledOrders.contains(orderId)

    fun isCancelled(orderId: Int): Boolean = isNonFilledTerminal(orderId)

    fun isNonFilledTerminal(orderId: Int): Boolean = cancelledOrders.contains(orderId)

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
        val orderStatus = OrderStatus.fromBrokerStatus(status)
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
        if (orderStatus == OrderStatus.FILLED) {
            filledOrders.add(orderId)
            if (avgFillPrice > 0.0) fillPrices[orderId] = BigDecimal(avgFillPrice).setScale(4, RoundingMode.HALF_UP)
        }
        if (orderStatus.isNonFilledTerminal) {
            cancelledOrders.add(orderId)
        }
        if (orderStatus.isTerminal) {
            terminalWaiters.remove(orderId)?.complete(orderStatus)
        }
        if (filled > BigDecimal.ZERO && remaining > BigDecimal.ZERO && orderStatus.isTerminal) {
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
        if (isNonFilledTerminal(orderId)) return latestUpdates[orderId]?.orderStatus ?: OrderStatus.CANCELLED
        val update = latestUpdates[orderId] ?: return null
        return update.orderStatus.takeIf { it.isTerminal }
    }

    private fun waiterFor(orderId: Int): CompletableDeferred<OrderStatus> =
        terminalWaiters.compute(orderId) { _, existing ->
            terminalStatus(orderId)?.let { return@compute CompletableDeferred(it) }
            existing?.takeUnless { it.isCompleted } ?: CompletableDeferred()
        } ?: CompletableDeferred(OrderStatus.PENDING)
}
