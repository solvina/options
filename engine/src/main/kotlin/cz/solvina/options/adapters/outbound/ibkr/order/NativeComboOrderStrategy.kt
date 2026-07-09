package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.Order
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionContractKey
import cz.solvina.options.adapters.outbound.ibkr.cache.resolveConIdOrCached
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.order.floorToOptionTick
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
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
            val bagContract = resolveBagContract(soldContract, boughtContract)

            // Create BUY order with negative limit (IBKR convention for net credit)
            val order = buildComboOrder(netCredit, qty)

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
                "[$exchangeId] Submitting NATIVE COMBO order: SELL ${soldContract.strike}${soldContract.type.ibkrCode} / " +
                    "BUY ${boughtContract.strike}${boughtContract.type.ibkrCode} " +
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
                "[$exchangeId] Failed to submit native combo order for ${soldContract.strike}${soldContract.type.ibkrCode}/" +
                    "${boughtContract.strike}${boughtContract.type.ibkrCode}: ${e.message}"
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

        // Both legs must be the same option type — a credit spread never mixes puts and calls.
        if (soldContract.type != boughtContract.type) {
            return ValidationResult(false, "Both legs must be the same option type (puts or calls)")
        }

        // Strike relationship depends on spread direction: bull put sells the HIGHER strike,
        // bear call sells the LOWER strike.
        when (soldContract.type) {
            OptionType.PUT ->
                if (soldContract.strike <= boughtContract.strike) {
                    return ValidationResult(false, "Short strike must be > long strike for put spread")
                }
            OptionType.CALL ->
                if (soldContract.strike >= boughtContract.strike) {
                    return ValidationResult(false, "Short strike must be < long strike for call spread")
                }
        }

        // Validate credit is positive
        if (netCredit.amount <= BigDecimal.ZERO) {
            return ValidationResult(false, "Net credit must be positive (you're receiving money)")
        }

        return ValidationResult(true)
    }

    override fun notes(): String = "Native combo order (atomic): both legs fill together or order cancelled. Most reliable."

    override fun supportsInPlaceModify(): Boolean = true

    override suspend fun modifySpreadPrice(
        existingOrderId: Int,
        soldContract: OptionContract,
        boughtContract: OptionContract,
        newCredit: Money,
        qty: Int,
    ) {
        val validation = validateOrder(soldContract, boughtContract, newCredit)
        require(validation.isValid) { "Cannot amend to an invalid price: ${validation.reason}" }

        // Re-`placeOrder` under the SAME orderId — IBKR treats this as a modification of the live
        // order, not a new one. No cancel is issued (no PendingCancel race) and the fill watcher
        // already registered against existingOrderId stays valid, so we deliberately do NOT touch
        // registry.pendingOrderStatus here.
        val bagContract = resolveBagContract(soldContract, boughtContract)
        val order = buildComboOrder(newCredit, qty)

        logger.info {
            "[$exchangeId] Amending NATIVE COMBO order $existingOrderId IN PLACE → net \$${newCredit.amount} " +
                "(lmtPrice=${order.lmtPrice()})"
        }
        client.placeOrder(existingOrderId, bagContract, order)
    }

    /**
     * Resolve both legs' conIds (from the cache; a 6s timeout > the cache's own 5s network timeout so
     * the cache governs the lookup and cleans up its own in-flight state — see E3) and assemble the
     * BAG parent contract.
     */
    private suspend fun resolveBagContract(
        soldContract: OptionContract,
        boughtContract: OptionContract,
    ): Contract {
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
            contractCache.resolveConIdOrCached(soldKey)
                ?: error("Sold contract not in cache and lookup timed out for $soldKey")
        val boughtConId =
            contractCache.resolveConIdOrCached(boughtKey)
                ?: error("Bought contract not in cache and lookup timed out for $boughtKey")

        return buildNativeComboContract(
            underlyingSymbol = soldContract.symbol.value,
            soldConId = soldConId,
            boughtConId = boughtConId,
        )
    }

    /** BUY order with a negative limit — IBKR's convention for accepting a net credit on a BAG. */
    private fun buildComboOrder(
        netCredit: Money,
        qty: Int,
    ): Order =
        Order().apply {
            action("BUY")
            orderType("LMT")
            lmtPrice(-netCredit.amount.floorToOptionTick().toDouble()) // Negative = net credit accepted
            totalQuantity(Decimal.get(qty.toLong()))
            tif("DAY")
            if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
        }

    private fun buildNativeComboContract(
        underlyingSymbol: String,
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
            // IBKR requires the BAG parent to carry the UNDERLYING symbol; an empty symbol is rejected
            // at order validation with error 321 ("The symbol or the local-symbol or the security id
            // must be entered"), which silently blocked every native combo entry. The conids on the
            // legs identify the actual options; this only names the combo's underlying.
            symbol(underlyingSymbol)
            secType("BAG")
            exchange(exchangeId)
            currency(currency)
            comboLegs(listOf(soldLeg, boughtLeg))
        }
    }
}
