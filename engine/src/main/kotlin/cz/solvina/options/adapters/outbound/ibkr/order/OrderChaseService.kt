package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOrdersRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderIdCounter
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.order.LegOrder
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.order.ceilToOptionTick
import cz.solvina.options.domain.features.order.floorToOptionTick
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.models.OptionContract
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.beans.factory.ObjectProvider
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
    // Lazy: fresh quotes for repricing. Absent in unit tests → reprice off the previous limit only.
    private val marketData: ObjectProvider<MarketDataPort>? = null,
) {
    suspend fun waitForFillOrChase(
        initialOrderId: Int,
        contract: Contract,
        action: String,
        initialPrice: BigDecimal,
        qty: Int,
        optionContract: OptionContract? = null,
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
                // 2026-11-27: orderChasePriceStep is DOLLARS (the yml always described it that way; the
                // code applied it as a percentage, so 0.01 meant +1%). The reprice also follows a fresh
                // mid when the market has moved past the old limit, instead of only nudging a stale price.
                val step = BigDecimal.valueOf(config.orderChasePriceStep).max(BigDecimal("0.01"))
                val fresh = freshMid(optionContract)
                price =
                    if (action == "BUY") {
                        val nudged = price.add(step)
                        (if (fresh != null) nudged.max(fresh) else nudged).ceilToOptionTick()
                    } else {
                        val nudged = price.subtract(step)
                        (if (fresh != null) nudged.min(fresh) else nudged).floorToOptionTick().max(BigDecimal("0.01"))
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

    private suspend fun freshMid(contract: OptionContract?): BigDecimal? {
        if (contract == null) return null
        val port = marketData?.ifAvailable ?: return null
        return runCatching { port.streamedOptionMid(contract)?.amount ?: port.getOptionMidLive(contract)?.amount }
            .getOrNull()
            ?.takeIf { it > BigDecimal.ZERO }
    }

    private suspend fun cancelAndWait(orderId: Int) {
        registry.markSelfCancelled(orderId)
        client.cancelOrder(orderId, OrderCancel())
        registry.awaitTerminal(orderId, 10.seconds)
        delay(500)
    }
}
