package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.flag.BracketOrderIds
import cz.solvina.options.domain.features.flag.BracketOrderPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

@Component
class IbkrBracketOrderAdapter(
    private val registry: IbkrOrderRegistry,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
    private val connectionConfig: IbkrConnectionConfig,
) : BracketOrderPort {

    override suspend fun submitBracketOrder(
        symbol: Symbol,
        shares: Int,
        entryPrice: BigDecimal,
        stopLossPrice: BigDecimal,
        profitTargetPrice: BigDecimal,
    ): BracketOrderIds {
        val contract = contractFactory.stockContract(symbol)
        val qty = Decimal.get(shares.toLong())
        val ocaGroup = "FLAG_${symbol.value}_${System.currentTimeMillis()}"

        val entryId = registry.nextOrderId()
        val slId = registry.nextOrderId()
        val ptId = registry.nextOrderId()

        // Parent: Stop-Market BUY
        val parent =
            Order().apply {
                action("BUY")
                orderType("STP")
                auxPrice(entryPrice.toDouble())
                totalQuantity(qty)
                tif("DAY")
                transmit(false)  // hold — submit as a group
                if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
            }

        // Child 1: Stop-Market SELL (stop loss)
        val stopLoss =
            Order().apply {
                action("SELL")
                orderType("STP")
                auxPrice(stopLossPrice.toDouble())
                totalQuantity(qty)
                tif("GTC")
                parentId(entryId)
                ocaGroup(ocaGroup)
                ocaType(1)  // cancel other orders on fill
                transmit(false)
                if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
            }

        // Child 2: Limit SELL (profit target) — transmit=true triggers the whole group
        val profitTarget =
            Order().apply {
                action("SELL")
                orderType("LMT")
                lmtPrice(profitTargetPrice.toDouble())
                totalQuantity(qty)
                tif("GTC")
                parentId(entryId)
                ocaGroup(ocaGroup)
                ocaType(1)
                transmit(true)  // transmit the entire bracket
                if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
            }

        // Register deferreds before placing orders
        registry.pendingOrderStatus[entryId] = CompletableDeferred()
        registry.pendingOrderStatus[slId] = CompletableDeferred()
        registry.pendingOrderStatus[ptId] = CompletableDeferred()

        logger.info {
            "[$symbol] Placing bracket order: entry=${entryPrice} SL=${stopLossPrice} PT=${profitTargetPrice} " +
                "qty=$shares entryId=$entryId slId=$slId ptId=$ptId ocaGroup=$ocaGroup"
        }

        client.placeOrder(entryId, contract, parent)
        client.placeOrder(slId, contract, stopLoss)
        client.placeOrder(ptId, contract, profitTarget)

        return BracketOrderIds(entryId, slId, ptId)
    }

    override suspend fun cancelOrder(orderId: Int) {
        logger.info { "Cancelling order $orderId" }
        registry.markSelfCancelled(orderId)
        client.cancelOrder(orderId, OrderCancel())
        // Give IBKR a moment to acknowledge; don't block on confirmation
        delay(200)
    }

    override suspend fun awaitParentFill(orderId: Int): OrderStatus = awaitFill(orderId)

    override suspend fun awaitChildFill(orderId: Int): OrderStatus = awaitFill(orderId)

    override suspend fun submitMarketSell(
        symbol: Symbol,
        shares: Int,
    ): Int {
        val contract = contractFactory.stockContract(symbol)
        val orderId = registry.nextOrderId()

        val order =
            Order().apply {
                action("SELL")
                orderType("MKT")
                totalQuantity(Decimal.get(shares.toLong()))
                tif("DAY")
                if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
            }

        registry.pendingOrderStatus[orderId] = CompletableDeferred()

        logger.info { "[$symbol] Market SELL $shares shares (orderId=$orderId)" }
        client.placeOrder(orderId, contract, order)
        return orderId
    }

    private suspend fun awaitFill(orderId: Int): OrderStatus {
        val deferred = registry.pendingOrderStatus[orderId] ?: return OrderStatus.CANCELLED
        return try {
            deferred.await()
        } finally {
            registry.pendingOrderStatus.remove(orderId)
        }
    }
}
