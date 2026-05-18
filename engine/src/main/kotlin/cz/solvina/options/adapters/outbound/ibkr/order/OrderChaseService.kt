package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.LegOrder
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.scanner.ScannerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

private val logger = KotlinLogging.logger {}

@Service
class OrderChaseService(
    private val registry: IbkrOrderRegistry,
    private val client: EClientSocket,
    private val config: ScannerConfig,
) {
    suspend fun waitForFillOrChase(
        initialOrderId: Int,
        contract: Contract,
        action: String,
        initialPrice: BigDecimal,
        qty: Int,
    ): LegOrder {
        var orderId = initialOrderId
        var price = initialPrice

        for (attempt in 0..config.orderChaseMaxRetries) {
            val deferred =
                registry.pendingOrderStatus[orderId]
                    ?: CompletableDeferred<OrderStatus>().also {
                        registry.pendingOrderStatus[orderId] = it
                    }

            val timeoutMs = config.orderChaseTimeoutMinutes * 60_000L
            val status =
                try {
                    withTimeout(timeoutMs) { deferred.await() }
                } catch (e: TimeoutCancellationException) {
                    logger.info { "Order $orderId timed out after ${config.orderChaseTimeoutMinutes}min, cancelling" }
                    cancelAndWait(orderId)
                    OrderStatus.CANCELLED
                }

            if (status == OrderStatus.FILLED) {
                logger.info { "Order $orderId filled at price $price" }
                return LegOrder(orderId, OrderStatus.FILLED)
            }

            if (attempt < config.orderChaseMaxRetries) {
                price =
                    price
                        .multiply(BigDecimal.ONE.subtract(BigDecimal(config.orderChasePriceStep)))
                        .setScale(2, RoundingMode.HALF_DOWN)
                orderId = registry.nextOrderId()
                logger.info { "Repricing: new orderId=$orderId price=$price (attempt ${attempt + 1}/${config.orderChaseMaxRetries})" }

                val newDeferred = CompletableDeferred<OrderStatus>()
                registry.pendingOrderStatus[orderId] = newDeferred

                val ibkrOrder =
                    Order().apply {
                        action(action)
                        orderType("LMT")
                        lmtPrice(price.toDouble())
                        totalQuantity(Decimal.get(qty.toLong()))
                        tif("DAY")
                    }
                client.placeOrder(orderId, contract, ibkrOrder)
            }
        }

        logger.warn { "Order not filled after ${config.orderChaseMaxRetries} retries" }
        return LegOrder(orderId, OrderStatus.CANCELLED)
    }

    private suspend fun cancelAndWait(orderId: Int) {
        registry.markSelfCancelled(orderId)
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
        delay(500)
    }
}
