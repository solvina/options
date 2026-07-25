package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.Order
import com.ib.client.OrderState
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

private val TERMINAL_STATUSES = setOf("cancelled", "filled", "apicancelled", "inactive")

/**
 * Updated from TWS on every order status update, the preferred way to track orders.
 */
@Component
class IbkrOrdersRegistry {
    private val openOrders = ConcurrentHashMap<Int, OpenOrder>()

    fun getAllOrders(): List<OpenOrder> = openOrders.values.toList()

    fun getOpenOrders(): List<OpenOrder> = openOrders.values.filter { it.status !in TERMINAL_STATUSES }

    fun onOpenOrder(
        orderId: Int,
        contract: Contract,
        order: Order,
        orderState: OrderState,
    ) {
        openOrders[orderId] =
            OpenOrder(
                orderId = orderId,
                symbol = contract.symbol() ?: "",
                action = order.action()?.toString() ?: "",
                orderType = order.orderType()?.toString() ?: "",
                limitPrice = order.lmtPrice().takeIf { !it.isNaN() && it != Double.MAX_VALUE },
                status = orderState.status()?.name ?: "",
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
        val order = openOrders[orderId] ?: return
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
    }
}
