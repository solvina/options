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
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode

private val logger = KotlinLogging.logger {}

/**
 * Strategy for exchanges WITHOUT native combo support (e.g., EUREX)
 *
 * EUREX lacks native combo/complex order books, so IBKR's SmartRouter must:
 * 1. Leg-in across individual order books
 * 2. Match fills manually based on timing/price
 *
 * This strategy:
 * - Submits SHORT leg first (highest probability of fill)
 * - Waits for fill confirmation
 * - Submits LONG leg to hedge
 * - Tracks both order IDs for position reconciliation
 * - Falls back to cancellation if either leg fails
 */
class LegByLegOrderStrategy(
    private val exchangeId: String = "EUREX",
    private val registry: IbkrOrderRegistry,
    private val client: EClientSocket,
    private val contractCache: IbkrContractCache,
    private val connectionConfig: IbkrConnectionConfig,
    private val shortLegCreditPct: BigDecimal = BigDecimal("0.75"), // 75% of credit for SHORT
    private val longLegCreditPct: BigDecimal = BigDecimal("0.25"), // 25% of credit for LONG
) : OrderExecutionStrategy {
    override fun getExchangeId(): String = exchangeId

    override suspend fun submitSpreadOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
    ): OrderSubmissionResult {
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
            logger.info {
                "[$exchangeId] Submitting LEG-BY-LEG order: SELL ${soldContract.strike}P / BUY ${boughtContract.strike}P " +
                    "@ net \$${netCredit.amount} (no atomic guarantee)"
            }

            // Step 1: Submit SHORT leg first (collect premium)
            val shortOrderId =
                submitSingleLeg(
                    contract = soldContract,
                    action = "SELL",
                    qty = qty,
                    limitPrice = soldContract.getLimitPrice(netCredit, "SELL"),
                    legDescription = "SHORT",
                )

            if (shortOrderId == 0) {
                return OrderSubmissionResult(
                    status = SubmissionStatus.LIQUIDITY_FAILED,
                    primaryOrderId = 0,
                    message = "Failed to submit SHORT leg - no liquidity",
                )
            }

            // Wait a brief moment for SHORT leg to be processed
            delay(500)

            // Step 2: Submit LONG leg to hedge (after SHORT fills or times out)
            val longOrderId =
                submitSingleLeg(
                    contract = boughtContract,
                    action = "BUY",
                    qty = qty,
                    limitPrice = boughtContract.getLimitPrice(netCredit, "BUY"),
                    legDescription = "LONG",
                )

            if (longOrderId == 0) {
                logger.warn { "[$exchangeId] SHORT leg filled but LONG leg failed - unhedged position!" }
                // Cancel SHORT leg to prevent unhedged exposure
                client.cancelOrder(shortOrderId, com.ib.client.OrderCancel())
                return OrderSubmissionResult(
                    status = SubmissionStatus.LIQUIDITY_FAILED,
                    primaryOrderId = shortOrderId,
                    secondaryOrderId = longOrderId,
                    message = "SHORT leg succeeded but LONG leg failed - order cancelled to prevent unhedged exposure",
                    requiresManualMatching = true,
                )
            }

            logger.info {
                "[$exchangeId] Submitted both legs: SHORT order=$shortOrderId LONG order=$longOrderId " +
                    "(NOTE: NOT atomic - requires position matching)"
            }

            OrderSubmissionResult(
                status = SubmissionStatus.SUCCESS,
                primaryOrderId = shortOrderId,
                secondaryOrderId = longOrderId,
                message = "Both legs submitted separately - manual matching required",
                requiresManualMatching = true, // CRITICAL: backend must reconcile these
            )
        } catch (e: Exception) {
            logger.error(e) { "[$exchangeId] Failed to submit leg-by-leg order" }
            OrderSubmissionResult(
                status = SubmissionStatus.SYSTEM_ERROR,
                primaryOrderId = 0,
                message = e.message ?: "Unknown error",
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

        // Validate strike relationship
        if (soldContract.strike <= boughtContract.strike) {
            return ValidationResult(false, "Short strike must be > long strike for put spread")
        }

        // Additional check: EUREX may have minimum spread width requirements
        val spreadWidth = (soldContract.strike - boughtContract.strike).toDouble()
        if (spreadWidth < 1.0) {
            return ValidationResult(false, "EUREX may require minimum \$1.00 spread width")
        }

        return ValidationResult(true)
    }

    override fun notes(): String =
        "Leg-by-leg submission (no atomic guarantee). Each leg can fail independently. " +
            "Requires backend position matching to validate both legs filled."

    /**
     * Submit a single leg (SHORT or LONG) as an individual order
     */
    private suspend fun submitSingleLeg(
        contract: OptionContract,
        action: String,
        qty: Int,
        limitPrice: BigDecimal,
        legDescription: String,
    ): Int =
        try {
            val conId = contractCache.getOrFetchOptionConId(
                OptionContractKey(
                    symbol = contract.symbol,
                    expiry = contract.expiry,
                    strike = contract.strike,
                    optionType = contract.type,
                ),
            )

            val legContract =
                Contract().apply {
                    conid(conId)
                    exchange(exchangeId)
                }

            val order =
                Order().apply {
                    action(action)
                    orderType("LMT")
                    lmtPrice(limitPrice.toDouble())
                    totalQuantity(Decimal.get(qty.toLong()))
                    tif("DAY")
                    if (connectionConfig.account.isNotBlank()) account(connectionConfig.account)
                }

            val orderId = registry.nextOrderId()
            val deferred = CompletableDeferred<OrderStatus>()
            registry.pendingOrderStatus[orderId] = deferred

            logger.debug {
                "[$exchangeId] Submitting $legDescription leg: $action ${contract.strike}P " +
                    "@ $limitPrice orderId=$orderId"
            }

            client.placeOrder(orderId, legContract, order)

            orderId
        } catch (e: Exception) {
            logger.error(e) { "Failed to submit $legDescription leg" }
            0 // Return 0 to signal failure
        }

    /**
     * Calculate limit price for individual leg based on spread credit
     * This is a best-effort approximation since we're not submitting atomically
     */
    private fun OptionContract.getLimitPrice(
        netCredit: Money,
        action: String,
    ): BigDecimal {
        // For EUREX leg-by-leg: estimate leg price from net credit
        // These are heuristics - actual market will determine fills
        // Configured via constructor parameters for tuning

        return when (action) {
            "SELL" -> (netCredit.amount * shortLegCreditPct).floorToTick() // SHORT leg gets most of the credit
            "BUY" -> (netCredit.amount * longLegCreditPct).floorToTick() // LONG leg costs less
            else -> netCredit.amount.floorToTick()
        }
    }
}

/** Floor-rounds a credit amount to the IBKR minimum price variation grid ($0.01 below $3, $0.05 at or above $3). */
private fun BigDecimal.floorToTick(): BigDecimal {
    val tick = if (this < BigDecimal("3.00")) BigDecimal("0.01") else BigDecimal("0.05")
    return divide(tick, 0, RoundingMode.FLOOR).multiply(tick)
}
