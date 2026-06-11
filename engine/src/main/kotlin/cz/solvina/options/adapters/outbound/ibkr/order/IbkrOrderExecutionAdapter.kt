package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.EClientSocket
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionContractKey
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrOrderExecutionAdapter(
    private val registry: IbkrOrderRegistry,
    private val client: EClientSocket,
    private val contractCache: IbkrContractCache,
    private val openOrdersAdapter: IbkrOpenOrdersAdapter,
    private val strategyRouter: ExchangeStrategyRouter,
    private val reconciliationService: PositionReconciliationService,
    private val orderReplacementService: OrderReplacementService,
) : OrderExecutionPort {
    override suspend fun submitComboLimitOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
    ): Int {
        // Route to exchange-specific strategy (default to SMART for dynamic routing)
        val result =
            strategyRouter.submitSpreadOrder(
                soldContract,
                boughtContract,
                netCredit,
                qty,
                exchange = "SMART",
            )

        return when {
            result.status == SubmissionStatus.SUCCESS && !result.requiresManualMatching -> {
                // US exchanges: atomic combo, ready to track
                logger.info {
                    "Order ${result.primaryOrderId} submitted via ${strategyRouter.getStrategyInfo(
                        "SMART",
                    )}"
                }
                result.primaryOrderId
            }
            result.status == SubmissionStatus.SUCCESS && result.requiresManualMatching -> {
                // EUREX: register for position reconciliation
                logger.info {
                    "Registered leg-by-leg order for reconciliation: SHORT=${result.primaryOrderId}, LONG=${result.secondaryOrderId}"
                }
                reconciliationService.registerPendingMatch(
                    result.primaryOrderId,
                    result.secondaryOrderId!!,
                    soldContract,
                    boughtContract,
                    qty,
                )
                result.primaryOrderId
            }
            else -> {
                logger.error { "Order submission failed: ${result.message}" }
                throw IllegalStateException("Failed to submit spread order: ${result.message}")
            }
        }
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
        // Use atomic replacement: verify old order is removed before submitting new
        val verificationSuccess = orderReplacementService.replacementCancel(existingOrderId)
        if (!verificationSuccess) {
            logger.warn { "Order replacement: old order $existingOrderId could not be verified as removed; risking double order" }
        }
        return submitComboLimitOrder(soldContract, boughtContract, newCredit, qty)
    }

    override suspend fun getSymbolsWithOpenOrders(): Set<Symbol> =
        runCatching { openOrdersAdapter.getOpenOrders() }
            .getOrDefault(emptyList())
            .map { Symbol(it.symbol) }
            .toSet()

    private suspend fun resolveConId(contract: OptionContract): Int =
        contractCache.getOrFetchOptionConId(
            OptionContractKey(
                symbol = contract.symbol,
                expiry = contract.expiry,
                strike = contract.strike,
                optionType = contract.type,
            ),
        )
}
