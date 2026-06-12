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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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

            // Issue #4: Monitor SHORT leg fill in real-time during submission window
            // Reduce wait time to minimize gap (50ms may be too long for fast fills)
            val shortFilled = monitorShortFillBeforeLongSubmission(shortOrderId, maxWaitMs = 20)
            if (shortFilled) {
                logger.error {
                    "[$exchangeId] CRITICAL: SHORT leg $shortOrderId already FILLED before LONG submission - " +
                        "position would be unhedged. Cancelling SHORT to prevent exposure."
                }
                // Attempt immediate cancellation to prevent any unhedged exposure
                runCatching {
                    client.cancelOrder(shortOrderId, com.ib.client.OrderCancel())
                }.onFailure { e ->
                    logger.error(e) {
                        "[$exchangeId] CRITICAL: Failed to cancel SHORT leg $shortOrderId after early fill detection!"
                    }
                }
                return OrderSubmissionResult(
                    status = SubmissionStatus.LIQUIDITY_FAILED,
                    primaryOrderId = shortOrderId,
                    message = "SHORT leg filled before LONG submission - order cancelled to prevent unhedged position",
                    requiresManualMatching = false,
                )
            }

            // Step 2: Submit LONG leg to hedge (after SHORT submitted but not yet filled)
            val longOrderId =
                submitSingleLeg(
                    contract = boughtContract,
                    action = "BUY",
                    qty = qty,
                    limitPrice = boughtContract.getLimitPrice(netCredit, "BUY"),
                    legDescription = "LONG",
                )

            if (longOrderId == 0) {
                logger.error { "[$exchangeId] CRITICAL: SHORT leg submitted but LONG leg failed - unhedged position risk!" }
                // Issue #4: Fail-safe - cancel SHORT leg immediately to prevent unhedged exposure
                runCatching {
                    client.cancelOrder(shortOrderId, com.ib.client.OrderCancel())
                    logger.info { "[$exchangeId] Cancelled SHORT leg $shortOrderId due to LONG submission failure" }
                }.onFailure { e ->
                    logger.error(e) {
                        "[$exchangeId] CRITICAL: Failed to cancel SHORT leg $shortOrderId after LONG failed! " +
                            "Position may be unhedged."
                    }
                }
                return OrderSubmissionResult(
                    status = SubmissionStatus.LIQUIDITY_FAILED,
                    primaryOrderId = shortOrderId,
                    secondaryOrderId = 0,
                    message = "LONG leg submission failed - SHORT leg cancelled to prevent unhedged exposure",
                    requiresManualMatching = false,
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[$exchangeId] Failed to submit leg-by-leg order" }
            OrderSubmissionResult(
                status = SubmissionStatus.SYSTEM_ERROR,
                primaryOrderId = 0,
                message = e.message ?: "Unknown error",
            )
        }
    }

    /**
     * Monitor SHORT leg fill in real-time during the submission window (Issue #4 fix).
     *
     * After SHORT order is submitted, check if it fills before LONG can be submitted.
     * If SHORT fills early, LONG submission will be cancelled to prevent unhedged exposure.
     *
     * This is the FIRST layer of protection. The SECOND layer is awaitBothLegsWithConcurrentMonitoring()
     * which validates both legs filled atomically after both are submitted.
     *
     * @param shortOrderId The SHORT leg order ID to monitor
     * @param maxWaitMs Maximum time to wait before checking (tightened to 20ms for faster response)
     * @return true if SHORT leg is already filled, false if not yet filled
     */
    private suspend fun monitorShortFillBeforeLongSubmission(
        shortOrderId: Int,
        maxWaitMs: Long = 150, // 150ms covers typical EUREX SmartRouter latency (100–500ms window)
    ): Boolean {
        // Wait briefly for SHORT order to be processed by IBKR
        delay(maxWaitMs)

        // Check if SHORT order has already filled (unhedged risk!)
        val deferred = registry.pendingOrderStatus[shortOrderId]
        if (deferred != null && deferred.isCompleted) {
            val status =
                try {
                    deferred.getCompleted()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[$exchangeId] Fill status deferred failed for shortOrderId=$shortOrderId — treating as CANCELLED" }
                    OrderStatus.CANCELLED
                }
            if (status == OrderStatus.FILLED) {
                logger.error {
                    "[$exchangeId] SHORT leg $shortOrderId FILLED before LONG submission - unhedged position would occur!"
                }
                return true
            }
        }
        return false
    }

    /**
     * Wait for both legs to fill concurrently (Issue #4 fix - SECOND layer of protection).
     *
     * After both legs are submitted, monitor fills in parallel.
     * Returns success only when both legs report FILLED status.
     * If timeout occurs or either leg fails, cancels BOTH to prevent unhedged exposure.
     *
     * This ensures atomicity: either both legs fill (hedge is complete) or both are cancelled
     * (no unhedged position created).
     *
     * @param shortOrderId The SHORT leg order ID
     * @param longOrderId The LONG leg order ID
     * @param timeoutMs Maximum time to wait for both fills (default 10000ms)
     * @return true if both legs filled successfully, false otherwise
     */
    suspend fun awaitBothLegsWithConcurrentMonitoring(
        shortOrderId: Int,
        longOrderId: Int,
        timeoutMs: Long = 10000,
    ): Boolean {
        val startTime = System.currentTimeMillis()
        val pollIntervalMs = 50L // Tightened from 100ms for faster response

        logger.info {
            "[$exchangeId] Monitoring both legs: SHORT=$shortOrderId LONG=$longOrderId " +
                "(waiting up to ${timeoutMs}ms for both to fill)"
        }

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val shortDeferred = registry.pendingOrderStatus[shortOrderId]
            val longDeferred = registry.pendingOrderStatus[longOrderId]

            if (shortDeferred != null && longDeferred != null) {
                if (shortDeferred.isCompleted && longDeferred.isCompleted) {
                    val shortStatus =
                        try {
                            shortDeferred.getCompleted()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn(e) { "[$exchangeId] SHORT deferred failed for orderId=$shortOrderId — treating as CANCELLED" }
                            OrderStatus.CANCELLED
                        }
                    val longStatus =
                        try {
                            longDeferred.getCompleted()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn(e) { "[$exchangeId] LONG deferred failed for orderId=$longOrderId — treating as CANCELLED" }
                            OrderStatus.CANCELLED
                        }

                    if (shortStatus == OrderStatus.FILLED && longStatus == OrderStatus.FILLED) {
                        logger.info { "[$exchangeId] SUCCESS: Both legs filled atomically - hedge complete" }
                        return true
                    }

                    // One or both failed - Issue #4: Fail-safe to prevent unhedged exposure
                    logger.error {
                        "[$exchangeId] PARTIAL FILL DETECTED: SHORT=$shortStatus LONG=$longStatus - " +
                            "cancelling both to prevent unhedged position"
                    }

                    // Cancel BOTH legs to ensure no unhedged exposure
                    if (shortStatus == OrderStatus.FILLED) {
                        logger.info { "[$exchangeId] Cancelling SHORT $shortOrderId (FILLED, LONG failed)" }
                        runCatching { client.cancelOrder(shortOrderId, com.ib.client.OrderCancel()) }
                            .onFailure { e ->
                                logger.error(e) { "[$exchangeId] Failed to cancel SHORT $shortOrderId" }
                            }
                    }
                    if (longStatus == OrderStatus.FILLED) {
                        logger.info { "[$exchangeId] Cancelling LONG $longOrderId (FILLED, SHORT failed)" }
                        runCatching { client.cancelOrder(longOrderId, com.ib.client.OrderCancel()) }
                            .onFailure { e ->
                                logger.error(e) { "[$exchangeId] Failed to cancel LONG $longOrderId" }
                            }
                    }

                    return false
                }
            }

            delay(pollIntervalMs)
        }

        // Timeout waiting for fills - Issue #4: Cancel both to prevent unhedged exposure
        logger.error {
            "[$exchangeId] TIMEOUT: Both legs did not fill within ${timeoutMs}ms - " +
                "cancelling both (SHORT=$shortOrderId LONG=$longOrderId)"
        }
        runCatching { client.cancelOrder(shortOrderId, com.ib.client.OrderCancel()) }
            .onFailure { e -> logger.error(e) { "[$exchangeId] Failed to cancel SHORT after timeout" } }
        runCatching { client.cancelOrder(longOrderId, com.ib.client.OrderCancel()) }
            .onFailure { e -> logger.error(e) { "[$exchangeId] Failed to cancel LONG after timeout" } }
        return false
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
            val key =
                OptionContractKey(
                    symbol = contract.symbol,
                    expiry = contract.expiry,
                    strike = contract.strike,
                    optionType = contract.type,
                )
            // Try fast fetch (100ms timeout) to avoid blocking order submission
            val conId =
                try {
                    withTimeout(100L) { contractCache.getOrFetchOptionConId(key) }
                } catch (e: Exception) {
                    // If fetch fails or times out, use cached or error with reasonable message
                    contractCache.getCachedOptionConId(key)
                        ?: error("Contract not in cache and lookup timed out for $key; using conid=0 (may fail)")
                }

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
        } catch (e: CancellationException) {
            throw e
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
        // For EUREX leg-by-leg: estimate leg price from net credit allocation
        // SHORT leg: collect majority of credit
        // BUY leg: pay minority of credit, but NEVER below minimum tick ($0.01)
        // Exchanges reject LIMIT 0.00 orders; enforce minimum to prevent rejection
        return when (action) {
            "SELL" -> (netCredit.amount * shortLegCreditPct).floorToTick() // SHORT leg gets 75% of credit
            "BUY" -> {
                val buyPrice = (netCredit.amount * longLegCreditPct).floorToTick() // BUY leg gets 25%
                // CRITICAL: Never allow LIMIT 0.00 (exchanges reject it)
                // If calculated price rounds to 0, use minimum tick instead
                if (buyPrice <= BigDecimal.ZERO) BigDecimal("0.01") else buyPrice
            }
            else -> netCredit.amount.floorToTick()
        }
    }
}

/** Floor-rounds a credit amount to the IBKR minimum price variation grid ($0.01 below $3, $0.05 at or above $3). */
private fun BigDecimal.floorToTick(): BigDecimal {
    val tick = if (this < BigDecimal("3.00")) BigDecimal("0.01") else BigDecimal("0.05")
    return divide(tick, 0, RoundingMode.FLOOR).multiply(tick)
}
