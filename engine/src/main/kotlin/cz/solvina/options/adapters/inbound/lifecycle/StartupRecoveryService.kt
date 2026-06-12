package cz.solvina.options.adapters.inbound.lifecycle

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.account.OpenOrder
import cz.solvina.options.adapters.outbound.ibkr.order.OrderCancellationService
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Component
class StartupRecoveryService(
    private val spreadPort: SpreadPort,
    private val orderRegistry: IbkrOrderRegistry,
    private val openOrdersAdapter: IbkrOpenOrdersAdapter,
    private val client: EClientSocket,
    private val orderCancellationService: OrderCancellationService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun recover() {
        val pendingSpreads = spreadPort.findByStatus(SpreadStatus.PENDING)
        val closingSpreads = spreadPort.findByStatus(SpreadStatus.CLOSING)
        if (pendingSpreads.isEmpty() && closingSpreads.isEmpty()) return

        val openOrders =
            runCatching { openOrdersAdapter.getOpenOrders() }
                .onFailure { e -> logger.warn(e) { "Recovery: could not fetch open IBKR orders" } }
                .getOrDefault(emptyList<OpenOrder>())
        val openOrderIds = openOrders.map { it.orderId }.toSet()

        // Cancel any orphaned orders for symbols stuck in CLOSING state.
        // BUY orders: placed by the previous engine run's close attempt; without cancellation
        //            the next retryClose would place a duplicate order for the same contract.
        // SELL orders: orphaned entry orders from failed trades; these can execute when the close
        //             completes, reversing the position (e.g., AMD bug: +18 LONG closed but -18 SHORT
        //             opened due to stale SELL orders filling simultaneously).
        if (closingSpreads.isNotEmpty()) {
            val closingSymbols = closingSpreads.map { it.symbol.value }.toSet()
            val staleBuyOrders = openOrders.filter { it.symbol in closingSymbols && it.action.equals("BUY", ignoreCase = true) }
            val staleSellOrders = openOrders.filter { it.symbol in closingSymbols && it.action.equals("SELL", ignoreCase = true) }
            val staleOrders = staleBuyOrders + staleSellOrders

            if (staleOrders.isNotEmpty()) {
                logger.info {
                    "Recovery: atomically cancelling ${staleBuyOrders.size} stale BUY + ${staleSellOrders.size} stale SELL orders"
                }

                val buyOrderIds = staleBuyOrders.map { it.orderId }
                if (buyOrderIds.isNotEmpty()) {
                    val buyResults = orderCancellationService.cancelOrdersAtomic(buyOrderIds, "recovery_stale_buy")
                    for (result in buyResults) {
                        if (result.success) {
                            logger.info { "Recovery: stale BUY order ${result.orderId} cancelled" }
                        } else {
                            logger.warn { "Recovery: stale BUY order ${result.orderId} cancellation failed: ${result.reason}" }
                        }
                    }
                }

                val sellOrderIds = staleSellOrders.map { it.orderId }
                if (sellOrderIds.isNotEmpty()) {
                    val sellResults = orderCancellationService.cancelOrdersAtomic(sellOrderIds, "recovery_stale_sell")
                    for (result in sellResults) {
                        if (result.success) {
                            logger.info { "Recovery: stale SELL order ${result.orderId} cancelled — prevents position reversal" }
                        } else {
                            logger.warn {
                                "Recovery: stale SELL order ${result.orderId} cancellation failed: ${result.reason} — position reversal risk"
                            }
                        }
                    }
                }
            } else {
                logger.info { "Recovery: ${closingSpreads.size} CLOSING spread(s) found — no orphaned orders to cancel" }
            }
        }

        if (pendingSpreads.isEmpty()) return
        logger.info { "Recovery: found ${pendingSpreads.size} PENDING spread(s)" }

        for (spread in pendingSpreads) {
            val orderId = spread.soldLeg.orderId
            if (orderId == 0) {
                logger.warn { "Recovery: PENDING spread ${spread.id} has no orderId, closing as unknown" }
                spreadPort.update(
                    spread.copy(status = SpreadStatus.CLOSED_REJECTED, closeReason = "recovery_no_order", closedAt = Instant.now()),
                )
                continue
            }

            if (orderId in openOrderIds) {
                // Order still open — register a deferred so the fill is handled
                val deferred = CompletableDeferred<OrderStatus>()
                orderRegistry.pendingOrderStatus[orderId] = deferred
                logger.info {
                    "Recovery: re-registered orderId=$orderId for ${spread.symbol} ${spread.soldLeg.contract.strike}P/${spread.boughtLeg.contract.strike}P"
                }

                scope.launch {
                    val status = runCatching { deferred.await() }.getOrDefault(OrderStatus.CANCELLED)
                    when (status) {
                        OrderStatus.FILLED -> {
                            spreadPort.update(spread.copy(status = SpreadStatus.OPEN))
                            logger.info { "Recovery: orderId=$orderId filled — spread ${spread.id} promoted to OPEN" }
                        }
                        else -> {
                            spreadPort.update(
                                spread.copy(
                                    status = SpreadStatus.CLOSED_TIMEOUT,
                                    closeReason = "recovered_cancelled",
                                    closedAt = Instant.now(),
                                ),
                            )
                            logger.info { "Recovery: orderId=$orderId cancelled/rejected — spread ${spread.id} closed" }
                        }
                    }
                    orderRegistry.pendingOrderStatus.remove(orderId)
                }
            } else {
                // Order not found in IBKR — filled or cancelled while engine was down
                logger.warn {
                    "Recovery: orderId=$orderId for ${spread.symbol} not in open orders — closing as recovery_unknown (check positions manually)"
                }
                spreadPort.update(
                    spread.copy(status = SpreadStatus.CLOSED_MANUAL, closeReason = "recovery_unknown", closedAt = Instant.now()),
                )
            }
        }
    }
}
