package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOrdersRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderIdCounter
import cz.solvina.options.domain.features.order.LegOrder
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.order.ceilToOptionTick
import cz.solvina.options.domain.features.order.floorToOptionTick
import cz.solvina.options.domain.features.scanner.ScannerConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Service
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

@Service
class OrderChaseService(
    private val registry: IbkrOrdersRegistry,
    private val ibkrOrderIdCounter: IbkrOrderIdCounter,
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
            var cancelledByTimeout = false
            val status = registry.awaitTerminal(orderId, config.orderChaseTimeoutMinutes.minutes)
            if (status == OrderStatus.PENDING) {
                logger.info { "Order $orderId timed out after ${config.orderChaseTimeoutMinutes}min, cancelling" }
                cancelledByTimeout = true
                cancelAndWait(orderId)
            }

            if (status == OrderStatus.FILLED) {
                logger.info { "Order $orderId filled at price $price" }
                return LegOrder(orderId, OrderStatus.FILLED)
            }

            // If the order failed-fast (e.g. code 399 after-hours records a terminal cancellation),
            // the timeout path didn't run, so cancel the IBKR order now before repricing.
            if (!cancelledByTimeout) cancelAndWait(orderId)

            // The fill can race the cancel: IBKR fills the order before processing the cancel
            // request. Repricing after such a fill would submit a SECOND order for the same leg and
            // double the position — honor the fill instead.
            if (registry.isFilled(orderId)) {
                logger.warn { "Order $orderId FILLED during cancellation — honoring the fill instead of repricing" }
                return LegOrder(orderId, OrderStatus.FILLED)
            }

            if (attempt < config.orderChaseMaxRetries) {
                // Reprice toward the marketable side: SELL walks the limit DOWN toward the bid, BUY
                // walks it UP toward the ask. Applying the SELL direction to a BUY (e.g. the short
                // leg's buy-back when closing a spread) would make the order progressively less
                // fillable instead of more.
                price =
                    if (action == "BUY") {
                        price
                            .multiply(BigDecimal.ONE.add(BigDecimal(config.orderChasePriceStep)))
                            .ceilToOptionTick()
                    } else {
                        price
                            .multiply(BigDecimal.ONE.subtract(BigDecimal(config.orderChasePriceStep)))
                            .floorToOptionTick()
                    }
                orderId = ibkrOrderIdCounter.nextOrderId()
                logger.info { "Repricing: new orderId=$orderId price=$price (attempt ${attempt + 1}/${config.orderChaseMaxRetries})" }

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
        registry.awaitTerminal(orderId, 10.seconds)
        delay(500)
    }
}
