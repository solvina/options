package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.EClientSocket
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.IbkrInstrumentsConfig
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
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
    private val alertPort: AlertPort,
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
                        )
                    if (!verification.success) {
                        // The leg-by-leg strategy only reports SUCCESS after BOTH order-level fill
                        // deferreds completed FILLED — that is authoritative. This account-level
                        // re-check can simply lag (position feed latency); treating its timeout as
                        // "rejected" would abandon (cancel) two orders that are already filled and
                        // record a real position as CLOSED_REJECTED, leaving it live in the account
                        // but untracked (no TP/SL/DTE). Trust the order-level fills, alert instead.
                        logger.error {
                            "Leg-by-leg account-level reconciliation lagging (SHORT=${result.primaryOrderId} " +
                                "LONG=${result.secondaryOrderId}): ${verification.message} — both legs already " +
                                "confirmed FILLED at the order level; tracking the position and alerting for manual verification"
                        }
                        alertPort.send(
                            AlertLevel.CRITICAL,
                            "Leg-by-leg position feed lag",
                            "Both legs of a leg-by-leg spread (SHORT=${result.primaryOrderId} LONG=${result.secondaryOrderId}) " +
                                "confirmed FILLED at the order level, but the account position feed did not reflect both " +
                                "within the verification window (${verification.message}). The spread is being tracked as " +
                                "filled — please verify the account position manually in TWS.",
                        )
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
                // No deferred left doesn't always mean cancelled — the entry may have been consumed
                // by another await while the order actually FILLED. The registry's fill record is
                // the authoritative tiebreaker.
                ?: return if (registry.isFilled(orderId)) OrderStatus.FILLED else OrderStatus.CANCELLED
        return try {
            deferred.await()
        } finally {
            registry.pendingOrderStatus.remove(orderId)
        }
    }

    override suspend fun cancelAndAwait(orderId: Int): OrderStatus {
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
        // A fill can race the cancel: IBKR fills the order before processing the cancel request.
        // Report the true terminal state so callers never record a filled order as aborted.
        return if (registry.isFilled(orderId)) {
            logger.warn { "Order $orderId FILLED during cancellation — reporting FILLED, not CANCELLED" }
            OrderStatus.FILLED
        } else {
            OrderStatus.CANCELLED
        }
    }

    override suspend fun replaceComboWithNewPrice(
        existingOrderId: Int,
        soldContract: OptionContract,
        boughtContract: OptionContract,
        newCredit: Money,
        qty: Int,
    ): Int {
        // Atomic replacement: must verify old order is completely removed (and not filled instead)
        // before submitting new. Prevents the double-fill scenario where both old and new orders fill.
        return when (val result = orderReplacementService.replacementCancel(existingOrderId)) {
            is ReplacementCancelResult.Removed -> {
                logger.info { "Order replacement verified: old order $existingOrderId removed, submitting replacement" }
                submitComboLimitOrder(soldContract, boughtContract, newCredit, qty, legQuotes = null)
            }
            is ReplacementCancelResult.Filled -> {
                // The old order actually filled while we tried to cancel it — it is now a real
                // position. Submitting a replacement would double it. Return the existing order id
                // unchanged so the caller's fill watcher (already registered against it) delivers
                // the FILLED status.
                logger.warn {
                    "Order replacement SKIPPED: old order $existingOrderId filled instead of cancelling — " +
                        "not submitting a replacement (would double the position)"
                }
                existingOrderId
            }
            is ReplacementCancelResult.Unverified -> {
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
        }
    }

    override suspend fun getSymbolsWithOpenOrders(): Set<Symbol> =
        runCatching { openOrdersAdapter.getOpenOrders() }
            .getOrDefault(emptyList())
            .map { Symbol(it.symbol) }
            .toSet()

    override fun consumeFillPrice(orderId: Int): java.math.BigDecimal? = registry.consumeFillPrice(orderId)
}
