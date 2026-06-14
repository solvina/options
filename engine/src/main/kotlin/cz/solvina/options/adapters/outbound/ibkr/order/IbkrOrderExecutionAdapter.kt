package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.EClientSocket
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.IbkrInstrumentsConfig
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.LegQuotes
import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.order.StrandedLongLegException
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
    private val openOrdersAdapter: IbkrOpenOrdersAdapter,
    private val strategyRouter: ExchangeStrategyRouter,
    private val reconciliationService: PositionReconciliationService,
    private val orderReplacementService: OrderReplacementService,
    private val instrumentsConfig: IbkrInstrumentsConfig,
) : OrderExecutionPort {
    override suspend fun submitComboLimitOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
        legQuotes: LegQuotes?,
    ): Int {
        // Determine actual exchange from instrument config (DTB for EU stocks, CBOE for US, etc)
        val exchange = instrumentsConfig.instruments[soldContract.symbol.value]?.optionExchange ?: "SMART"

        // Route to exchange-specific strategy
        val result =
            strategyRouter.submitSpreadOrder(
                soldContract,
                boughtContract,
                netCredit,
                qty,
                exchange = exchange,
                legQuotes = legQuotes,
            )

        return when (result.status) {
            SubmissionStatus.SUCCESS -> {
                // Leg-by-leg success reports both order ids; before declaring the spread real, confirm
                // BOTH legs at the ACCOUNT level (independent of order-fill callbacks). Native combo
                // returns secondaryOrderId == null and needs no extra reconciliation.
                if (result.secondaryOrderId != null) {
                    val verification =
                        reconciliationService.verifyBothLegsFilled(
                            soldContract = soldContract,
                            boughtContract = boughtContract,
                            qty = qty,
                            shortOrderId = result.primaryOrderId,
                            longOrderId = result.secondaryOrderId,
                        )
                    if (!verification.success) {
                        logger.error {
                            "Leg-by-leg reconciliation failed (SHORT=${result.primaryOrderId} LONG=${result.secondaryOrderId}): " +
                                "${verification.message} — abandoning"
                        }
                        reconciliationService.abandonMatch(result.primaryOrderId, result.secondaryOrderId)
                        throw IllegalStateException("Leg-by-leg reconciliation failed: ${verification.message}")
                    }
                }
                logger.info { "Order ${result.primaryOrderId} submitted via ${strategyRouter.getStrategyInfo(exchange)}" }
                result.primaryOrderId
            }
            SubmissionStatus.STRANDED_LONG -> {
                // Protective LONG filled, SHORT did not, auto-unwind off: a bounded long-debit position
                // is open (never a naked short). Surface it so the entry is recorded as BROKEN_LONG_ONLY.
                logger.error { "Stranded LONG leg ${result.primaryOrderId}: ${result.message}" }
                throw StrandedLongLegException(result.primaryOrderId, result.message)
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
        // Atomic replacement: must verify old order is completely removed before submitting new
        // Prevents double-fill scenario where both old and new orders execute
        val verificationSuccess = orderReplacementService.replacementCancel(existingOrderId)

        if (!verificationSuccess) {
            // CRITICAL: Old order still in IBKR — DO NOT submit new order
            // Risk: Both orders could fill simultaneously, doubling position
            logger.error {
                "Order replacement BLOCKED: old order $existingOrderId still in IBKR after verification attempts. " +
                    "Will not submit replacement to prevent double-order scenario."
            }
            throw IllegalStateException(
                "Order replacement failed: could not verify removal of old order $existingOrderId. " +
                    "Check IBKR manually before retrying.",
            )
        }

        logger.info { "Order replacement verified: old order $existingOrderId removed, submitting replacement" }
        return submitComboLimitOrder(soldContract, boughtContract, newCredit, qty, legQuotes = null)
    }

    override suspend fun getSymbolsWithOpenOrders(): Set<Symbol> =
        runCatching { openOrdersAdapter.getOpenOrders() }
            .getOrDefault(emptyList())
            .map { Symbol(it.symbol) }
            .toSet()
}
