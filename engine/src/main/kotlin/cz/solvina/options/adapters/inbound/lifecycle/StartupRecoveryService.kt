package cz.solvina.options.adapters.inbound.lifecycle

import com.ib.client.EClientSocket
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.account.OpenOrder
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

        // Cancel any orphaned BUY orders for symbols stuck in CLOSING state.
        // These were placed by the previous engine run's close attempt; without cancellation
        // the next retryClose would place a duplicate order for the same contract.
        if (closingSpreads.isNotEmpty()) {
            val closingSymbols = closingSpreads.map { it.symbol.value }.toSet()
            val staleOrders = openOrders.filter { it.symbol in closingSymbols && it.action.equals("BUY", ignoreCase = true) }
            for (order in staleOrders) {
                logger.info {
                    "Recovery: cancelling stale CLOSING order ${order.orderId} (${order.symbol} ${order.action} @ ${order.limitPrice})"
                }
                runCatching { client.cancelOrder(order.orderId, OrderCancel()) }
                    .onFailure { e -> logger.warn(e) { "Recovery: failed to cancel order ${order.orderId}" } }
            }
            if (staleOrders.isEmpty()) {
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
