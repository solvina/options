package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderIdCounter
import cz.solvina.options.domain.features.account.AccountOpenOrder
import cz.solvina.options.domain.features.account.AccountOrderNotCancellableException
import cz.solvina.options.domain.features.account.AccountOrderNotFoundException
import cz.solvina.options.domain.features.account.AccountTradingPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

@Component
class IbkrAccountTradingAdapter(
    private val registry: IbkrOrdersRegistry,
    private val client: EClientSocket,
    private val ibkrOrderIdCounter: IbkrOrderIdCounter,
    private val config: IbkrConnectionConfig,
) : AccountTradingPort {
    override suspend fun getOpenOrders(): List<AccountOpenOrder> =
        registry.getOpenOrders().map {
            val blockedReason = cancelBlockedReason(it)
            AccountOpenOrder(
                orderId = it.orderId,
                symbol = it.symbol,
                action = it.action,
                orderType = it.orderType,
                status = it.status,
                limitPrice = it.limitPrice?.toBigDecimal(),
                clientId = it.clientId,
                cancellable = blockedReason == null,
                cancelBlockedReason = blockedReason,
            )
        }

    override suspend fun cancelOrder(orderId: Int) {
        val order = registry.getAllOrders().firstOrNull { it.orderId == orderId } ?: throw AccountOrderNotFoundException(orderId)
        cancelBlockedReason(order)?.let { throw AccountOrderNotCancellableException(orderId, it) }
        logger.info { "Cancelling account-page order $orderId" }
        registry.markSelfCancelled(orderId)
        client.cancelOrder(orderId, OrderCancel())
    }

    override suspend fun closePosition(
        conId: Int,
        quantity: BigDecimal,
    ) {
        val action = if (quantity < BigDecimal.ZERO) "BUY" else "SELL"
        val qty = quantity.abs().toLong()
        val orderId = ibkrOrderIdCounter.nextOrderId()
        val contract =
            Contract().apply {
                conid(conId)
                exchange("SMART")
            }
        val order =
            Order().apply {
                action(action)
                orderType("MKT")
                totalQuantity(Decimal.get(qty))
                tif("DAY")
            }

        logger.info { "Closing position conId=$conId qty=$quantity -> $action $qty orderId=$orderId" }
        client.placeOrder(orderId, contract, order)
    }

    private fun cancelBlockedReason(order: OpenOrder): String? =
        when {
            order.orderId <= 0 ->
                "IBKR has not assigned this order an API order id yet; bind it with clientId=0 or cancel it in TWS"
            order.clientId == null ->
                "IBKR has not reported the owning client id for this order yet"
            order.clientId != config.clientId ->
                "Order belongs to IBKR API client ${order.clientId}; this engine is client ${config.clientId}"
            else -> null
        }
}
