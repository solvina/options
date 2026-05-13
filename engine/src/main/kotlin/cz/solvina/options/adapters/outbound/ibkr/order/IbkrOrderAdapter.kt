package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionContractKey
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.LegOrder
import cz.solvina.options.domain.features.order.OrderPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrOrderAdapter(
    private val registry: IbkrOrderRegistry,
    private val client: EClientSocket,
    private val contractCache: IbkrContractCache,
    private val chaseService: OrderChaseService,
    private val contractFactory: IbkrContractFactory,
) : OrderPort {
    override suspend fun placeAndAwaitFill(
        contract: OptionContract,
        action: LegAction,
        limitPrice: Money,
        qty: Int,
    ): LegOrder {
        val conId =
            runCatching {
                contractCache.getOrFetchOptionConId(
                    OptionContractKey(contract.symbol, contract.expiry, contract.strike, contract.type),
                )
            }.getOrNull()

        val ibkrContract = buildIbkrContract(contract, conId)

        val orderId = registry.nextOrderId()
        val deferred = CompletableDeferred<OrderStatus>()
        registry.pendingOrderStatus[orderId] = deferred

        val ibkrOrder =
            Order().apply {
                action(action.name)
                orderType("LMT")
                lmtPrice(limitPrice.amount.toDouble())
                totalQuantity(Decimal.get(qty.toLong()))
                tif("DAY")
            }

        logger.info {
            "Placing ${action.name} ${contract.symbol} ${contract.strike}P ${contract.expiry} " +
                "@ \$${limitPrice.amount} orderId=$orderId"
        }
        client.placeOrder(orderId, ibkrContract, ibkrOrder)

        return chaseService.waitForFillOrChase(
            initialOrderId = orderId,
            contract = ibkrContract,
            action = action.name,
            initialPrice = limitPrice.amount,
            qty = qty,
        )
    }

    override suspend fun placeMarketOrder(
        contract: OptionContract,
        action: LegAction,
        qty: Int,
    ): LegOrder {
        val conId =
            runCatching {
                contractCache.getOrFetchOptionConId(
                    OptionContractKey(contract.symbol, contract.expiry, contract.strike, contract.type),
                )
            }.getOrNull()

        val ibkrContract = buildIbkrContract(contract, conId)
        val orderId = registry.nextOrderId()
        val deferred = CompletableDeferred<OrderStatus>()
        registry.pendingOrderStatus[orderId] = deferred

        val ibkrOrder =
            Order().apply {
                action(action.name)
                orderType("MKT")
                totalQuantity(Decimal.get(qty.toLong()))
                tif("DAY")
            }

        logger.info {
            "Placing MKT ${action.name} ${contract.symbol} ${contract.strike}P ${contract.expiry} orderId=$orderId"
        }
        client.placeOrder(orderId, ibkrContract, ibkrOrder)

        val status = withTimeout(30_000L) { deferred.await() }
        return LegOrder(orderId = orderId, status = status)
    }

    override suspend fun cancelOrder(orderId: Int) {
        logger.info { "Cancelling order $orderId" }
        client.cancelOrder(orderId, OrderCancel())
    }

    private fun buildIbkrContract(
        contract: OptionContract,
        conId: Int?,
    ) = contractFactory.optionContract(contract).also { c ->
        if (conId != null) c.conid(conId)
    }
}
