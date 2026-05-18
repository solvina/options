package cz.solvina.options.adapters.inbound.lifecycle

import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun recover() {
        val pendingSpreads = spreadPort.findByStatus(SpreadStatus.PENDING)
        if (pendingSpreads.isEmpty()) return

        logger.info { "Recovery: found ${pendingSpreads.size} PENDING spread(s)" }

        val openOrderIds =
            runCatching { openOrdersAdapter.getOpenOrders().map { it.orderId }.toSet() }
                .onFailure { e -> logger.warn(e) { "Recovery: could not fetch open IBKR orders" } }
                .getOrDefault(emptySet())

        for (spread in pendingSpreads) {
            val orderId = spread.soldLeg.orderId
            if (orderId == 0) {
                logger.warn { "Recovery: PENDING spread ${spread.id} has no orderId, closing as unknown" }
                spreadPort.update(
                    spread.copy(status = SpreadStatus.CLOSED_MANUAL, closeReason = "recovery_no_order", closedAt = Instant.now()),
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
                                    status = SpreadStatus.CLOSED_MANUAL,
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
