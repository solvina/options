package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionContractKey
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

/**
 * Strategy for US options exchanges (CBOE, ISE, etc.)
 *
 * These exchanges support native combo/complex orders:
 * - Both legs are guaranteed to fill together or not at all
 * - Single order submission with atomic execution
 * - Most reliable approach for credit spreads
 */
class NativeComboOrderStrategy(
    private val exchangeId: String = "CBOE",
    private val registry: IbkrOrderRegistry,
    private val client: EClientSocket,
    private val contractCache: IbkrContractCache,
    private val connectionConfig: IbkrConnectionConfig,
) : OrderExecutionStrategy {
    override fun getExchangeId(): String = exchangeId

    override suspend fun submitSpreadOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
        legQuotes: cz.solvina.options.domain.features.order.LegQuotes?,
    ): OrderSubmissionResult {
        // Native combo prices off the net credit; per-leg quotes are not needed here.
        // Validate first
        val validation = validateOrder(soldContract, boughtContract, netCredit)
        if (!validation.isValid) {
            return OrderSubmissionResult(
                status = SubmissionStatus.REJECTED,
                primaryOrderId = 0,
                message = validation.reason,
            )
        }

        return try {
            // Build native combo contract. 6s > the cache's own 5s network timeout so the cache
            // governs the lookup and cleans up its own in-flight state (a sub-second timeout here
            // cancels the cache mid-lookup and used to orphan its in-flight deferred — see E3).
            val soldKey =
                OptionContractKey(
                    symbol = soldContract.symbol,
                    expiry = soldContract.expiry,
                    strike = soldContract.strike,
                    optionType = soldContract.type,
                )
            val boughtKey =
                OptionContractKey(
                    symbol = boughtContract.symbol,
                    expiry = boughtContract.expiry,
                    strike = boughtContract.strike,
                    optionType = boughtContract.type,
                )
            val soldConId =
                try {
                    withTimeout(6_000L) { contractCache.getOrFetchOptionConId(soldKey) }
                } catch (e: Exception) {
                    contractCache.getCachedOptionConId(soldKey)
                        ?: error("Sold contract not in cache and lookup timed out for $soldKey")
                }
            val boughtConId =
                try {
                    withTimeout(6_000L) { contractCache.getOrFetchOptionConId(boughtKey) }
                } catch (e: Exception) {
                    contractCache.getCachedOptionConId(boughtKey)
                        ?: error("Bought contract not in cache and lookup timed out for $boughtKey")
                }

            val bagContract =
                buildNativeComboContract(
                    soldConId = soldConId,
                    boughtConId = boughtConId,
                )

            // Create BUY order with negative limit (IBKR convention for net credit)
            val order =
                Order().apply {
                    action("BUY")
                    orderType("LMT")
                    lmtPrice(-netCredit.amount.floorToTick().toDouble()) // Negative = net credit accepted
                    totalQuantity(Decimal.get(qty.toLong()))
                    tif("DAY")
                    if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
                }

            val orderId = registry.nextOrderId()
            if (orderId == 0) {
                // Order-id sequence not yet seeded by IBKR — submitting with id 0 would be invalid (E10).
                logger.error { "[$exchangeId] nextOrderId returned 0 — order id sequence not ready, cannot submit" }
                return OrderSubmissionResult(
                    status = SubmissionStatus.SYSTEM_ERROR,
                    primaryOrderId = 0,
                    message = "Order id sequence not ready (nextOrderId=0)",
                )
            }
            val deferred = CompletableDeferred<OrderStatus>()
            registry.pendingOrderStatus[orderId] = deferred

            logger.info {
                "[$exchangeId] Submitting NATIVE COMBO order: SELL ${soldContract.strike}P / BUY ${boughtContract.strike}P " +
                    "@ net \$${netCredit.amount} orderId=$orderId (both legs atomic)"
            }

            // Place order with IBKR
            try {
                client.placeOrder(orderId, bagContract, order)
                logger.debug {
                    "[$exchangeId] Order $orderId placed to IBKR: lmtPrice=${order.lmtPrice()} " +
                        "qty=${order.totalQuantity()} action=${order.action()} type=${order.orderType()}"
                }
            } catch (e: Exception) {
                // IBKR placeOrder can throw if there's a connection issue or immediate validation error
                logger.error(e) { "[$exchangeId] Error placing order to IBKR" }
                throw e
            }

            OrderSubmissionResult(
                status = SubmissionStatus.SUCCESS,
                primaryOrderId = orderId,
                secondaryOrderId = null,
                requiresManualMatching = false,
            )
        } catch (e: Exception) {
            logger.error(e) {
                "[$exchangeId] Failed to submit native combo order for ${soldContract.strike}P/${boughtContract.strike}P: ${e.message}"
            }
            OrderSubmissionResult(
                status = SubmissionStatus.SYSTEM_ERROR,
                primaryOrderId = 0,
                message = "${e::class.simpleName}: ${e.message}",
            )
        }
    }

    override fun validateOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
    ): ValidationResult {
        // Validate expiry matches
        if (soldContract.expiry != boughtContract.expiry) {
            return ValidationResult(false, "Legs must have same expiry date")
        }

        // Validate strike relationship (sold should be higher for put spreads)
        if (soldContract.strike <= boughtContract.strike) {
            return ValidationResult(false, "Short strike must be > long strike for put spread")
        }

        // Validate credit is positive
        if (netCredit.amount <= BigDecimal.ZERO) {
            return ValidationResult(false, "Net credit must be positive (you're receiving money)")
        }

        return ValidationResult(true)
    }

    override fun notes(): String = "Native combo order (atomic): both legs fill together or order cancelled. Most reliable."

    private fun buildNativeComboContract(
        soldConId: Int,
        boughtConId: Int,
    ): Contract {
        val soldLeg =
            com.ib.client.ComboLeg().apply {
                conid(soldConId)
                ratio(1)
                action("SELL")
                exchange(exchangeId)
            }

        val boughtLeg =
            com.ib.client.ComboLeg().apply {
                conid(boughtConId)
                ratio(1)
                action("BUY")
                exchange(exchangeId)
            }

        // Native combo is only routed to US exchanges (CBOE/ISE/AMEX/SMART), so currency is always
        // USD (the EUREX/DTB path uses LegByLegOrderStrategy) — X2.
        val currency = "USD"

        return Contract().apply {
            symbol("") // Empty for BAG orders
            secType("BAG")
            exchange(exchangeId)
            currency(currency)
            comboLegs(listOf(soldLeg, boughtLeg))
        }
    }
}
