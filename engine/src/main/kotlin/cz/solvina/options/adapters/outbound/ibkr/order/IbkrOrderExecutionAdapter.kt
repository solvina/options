package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.ComboLeg
import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionContractKey
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrOrderExecutionAdapter(
    private val registry: IbkrOrderRegistry,
    private val client: EClientSocket,
    private val contractCache: IbkrContractCache,
    private val contractFactory: IbkrContractFactory,
) : OrderExecutionPort {
    override suspend fun submitComboLimitOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
    ): Int {
        val soldConId = resolveConId(soldContract)
        val boughtConId = resolveConId(boughtContract)

        val bag = buildBagContract(soldContract, soldConId, boughtConId)
        val order =
            Order().apply {
                // BUY the combo = receive net credit (IBKR BAG convention for credit spreads)
                action("BUY")
                orderType("LMT")
                lmtPrice(netCredit.amount.floorToTick().toDouble())
                totalQuantity(Decimal.get(qty.toLong()))
                tif("DAY")
            }

        val orderId = registry.nextOrderId()
        val deferred = CompletableDeferred<OrderStatus>()
        registry.pendingOrderStatus[orderId] = deferred

        logger.info {
            "Placing BAG order: SELL ${soldContract.strike}P / BUY ${boughtContract.strike}P " +
                "@ net \$${netCredit.amount} orderId=$orderId"
        }
        client.placeOrder(orderId, bag, order)
        return orderId
    }

    override suspend fun awaitFill(orderId: Int): OrderStatus {
        val deferred =
            registry.pendingOrderStatus[orderId]
                ?: return OrderStatus.CANCELLED
        return try {
            deferred.await()
        } finally {
            registry.pendingOrderStatus.remove(orderId)
        }
    }

    override suspend fun cancelAndAwait(orderId: Int) {
        logger.info { "Cancelling BAG order $orderId" }
        client.cancelOrder(orderId, OrderCancel())
        runCatching {
            withTimeout(10_000L) {
                val deferred = registry.pendingOrderStatus[orderId]
                if (deferred != null && !deferred.isCompleted) {
                    deferred.await()
                }
            }
        }.onFailure {
            registry.pendingOrderStatus.remove(orderId)?.complete(OrderStatus.CANCELLED)
        }
        delay(200)
    }

    override suspend fun replaceComboWithNewPrice(
        existingOrderId: Int,
        soldContract: OptionContract,
        boughtContract: OptionContract,
        newCredit: Money,
        qty: Int,
    ): Int {
        cancelAndAwait(existingOrderId)
        return submitComboLimitOrder(soldContract, boughtContract, newCredit, qty)
    }

    private suspend fun resolveConId(contract: OptionContract): Int =
        contractCache.getOrFetchOptionConId(
            OptionContractKey(
                symbol = contract.symbol,
                expiry = contract.expiry,
                strike = contract.strike,
                optionType = contract.type,
            ),
        )

    private fun buildBagContract(
        soldContract: OptionContract,
        soldConId: Int,
        boughtConId: Int,
    ): Contract {
        val soldLeg =
            ComboLeg().apply {
                conid(soldConId)
                ratio(1)
                action("SELL")
                exchange("SMART")
            }
        val boughtLeg =
            ComboLeg().apply {
                conid(boughtConId)
                ratio(1)
                action("BUY")
                exchange("SMART")
            }
        return Contract().apply {
            symbol(soldContract.symbol.value)
            secType("BAG")
            currency(contractFactory.defFor(soldContract.symbol).currency)
            exchange("SMART")
            comboLegs(
                listOf(soldLeg, boughtLeg),
            )
        }
    }
}

/** Floor-rounds a credit amount to the IBKR minimum price variation grid ($0.01 below $3, $0.05 at or above $3). */
private fun BigDecimal.floorToTick(): BigDecimal {
    val tick = if (this < BigDecimal("3.00")) BigDecimal("0.01") else BigDecimal("0.05")
    return divide(tick, 0, RoundingMode.FLOOR).multiply(tick)
}
