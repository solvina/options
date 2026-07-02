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
import cz.solvina.options.domain.features.order.LegQuotes
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.order.ceilToOptionTick
import cz.solvina.options.domain.features.order.floorToOptionTick
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

/**
 * Strategy for exchanges WITHOUT native combo support (e.g. EUREX).
 *
 * EUREX has no combo/complex order book, so a spread must be legged in across individual books.
 * Correctness rule: NEVER hold a naked short. This strategy therefore:
 *
 * 1. Submits the protective LONG (BUY) leg FIRST and waits for it to fill.
 *    - If the long never fills, no short is ever submitted → zero exposure, clean abort.
 * 2. Only then submits the SHORT (SELL) leg and waits for it to fill.
 *    - Both fill → SUCCESS (a real, verified spread).
 *    - Short fails → the worst case is a paid-for long put (bounded debit), never a naked short.
 *      Handling is controlled by [IbkrConnectionConfig.unwindStrandedLongLeg]: keep + flag, or
 *      auto-unwind by selling the long back.
 *
 * Each leg is priced from its OWN fresh quote (long at the ask, short at the bid) so it is
 * marketable, rather than splitting a synthetic net credit across the legs.
 */
class LegByLegOrderStrategy(
    private val exchangeId: String = "EUREX",
    private val registry: IbkrOrderRegistry,
    private val client: EClientSocket,
    private val contractCache: IbkrContractCache,
    private val connectionConfig: IbkrConnectionConfig,
    private val unwindStrandedLongLeg: Boolean = false,
    private val legFillTimeoutMs: Long = 30_000,
    // Fallback net-credit split, used only when fresh per-leg quotes are unavailable.
    private val shortLegCreditPct: BigDecimal = BigDecimal("0.75"),
    private val longLegCreditPct: BigDecimal = BigDecimal("0.25"),
) : OrderExecutionStrategy {
    override fun getExchangeId(): String = exchangeId

    override suspend fun submitSpreadOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
        legQuotes: LegQuotes?,
    ): OrderSubmissionResult {
        val validation = validateOrder(soldContract, boughtContract, netCredit)
        if (!validation.isValid) {
            return OrderSubmissionResult(SubmissionStatus.REJECTED, primaryOrderId = 0, message = validation.reason)
        }

        val longLimit = longLegLimitPrice(legQuotes, netCredit)
        val shortLimit = shortLegLimitPrice(legQuotes, netCredit)
        if (shortLimit <= BigDecimal.ZERO) {
            return OrderSubmissionResult(
                SubmissionStatus.REJECTED,
                primaryOrderId = 0,
                message = "Short leg has no positive bid to sell into (shortLimit=$shortLimit)",
            )
        }

        return try {
            logger.info {
                "[$exchangeId] LEG-BY-LEG entry (LONG-first): " +
                    "BUY ${boughtContract.strike}${boughtContract.type.ibkrCode} @ \$$longLimit then " +
                    "SELL ${soldContract.strike}${soldContract.type.ibkrCode} @ \$$shortLimit (net target \$${netCredit.amount})"
            }

            // ---- Step 1: protective LONG leg first ----
            val longOrderId = submitSingleLeg(boughtContract, "BUY", qty, longLimit, "LONG")
            if (longOrderId == 0) {
                return OrderSubmissionResult(
                    SubmissionStatus.LIQUIDITY_FAILED,
                    primaryOrderId = 0,
                    message = "Protective LONG leg submission failed — no short submitted (no exposure)",
                )
            }

            val longStatus = awaitLegFill(longOrderId)
            if (longStatus != OrderStatus.FILLED) {
                logger.warn {
                    "[$exchangeId] Protective LONG $longOrderId did not fill (status=$longStatus) — cancelling, " +
                        "no SHORT submitted (no exposure)"
                }
                cancelQuietly(longOrderId)
                return OrderSubmissionResult(
                    SubmissionStatus.LIQUIDITY_FAILED,
                    primaryOrderId = longOrderId,
                    message = "Protective LONG leg did not fill — aborted before submitting SHORT (no exposure)",
                )
            }

            // ---- Step 2: SHORT leg, now that the hedge is in place ----
            val shortOrderId = submitSingleLeg(soldContract, "SELL", qty, shortLimit, "SHORT")
            if (shortOrderId == 0) {
                return strandedLong(longOrderId, boughtContract, qty, legQuotes, "SHORT leg submission failed")
            }

            val shortStatus = awaitLegFill(shortOrderId)
            if (shortStatus != OrderStatus.FILLED) {
                logger.warn { "[$exchangeId] SHORT $shortOrderId did not fill (status=$shortStatus) — cancelling" }
                cancelQuietly(shortOrderId)
                return strandedLong(longOrderId, boughtContract, qty, legQuotes, "SHORT leg did not fill")
            }

            logger.info {
                "[$exchangeId] SUCCESS: both legs filled — LONG=$longOrderId SHORT=$shortOrderId (verified spread)"
            }
            // The LONG leg's fill deferred is no longer needed — the caller consumes only the
            // SHORT (primary) order id. Drop it so pendingOrderStatus doesn't leak an entry (E8).
            registry.pendingOrderStatus.remove(longOrderId)
            OrderSubmissionResult(
                status = SubmissionStatus.SUCCESS,
                primaryOrderId = shortOrderId,
                secondaryOrderId = longOrderId,
                message = "Both legs filled (LONG-first leg-in)",
                requiresManualMatching = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[$exchangeId] Failed to submit leg-by-leg order" }
            OrderSubmissionResult(SubmissionStatus.SYSTEM_ERROR, primaryOrderId = 0, message = e.message ?: "Unknown error")
        }
    }

    /**
     * Handle the bounded worst case: the protective LONG filled but the SHORT did not.
     * This is never a naked short. Either auto-unwind the long, or keep it open and flag it.
     */
    private suspend fun strandedLong(
        longOrderId: Int,
        boughtContract: OptionContract,
        qty: Int,
        legQuotes: LegQuotes?,
        reason: String,
    ): OrderSubmissionResult {
        // The LONG leg's fill deferred won't be awaited further on this path (no success handoff);
        // drop it so pendingOrderStatus doesn't leak (E8).
        registry.pendingOrderStatus.remove(longOrderId)
        if (unwindStrandedLongLeg) {
            val sellBack = legQuotes?.boughtBid?.floorToOptionTick()?.coerceAtLeast(BigDecimal("0.01")) ?: BigDecimal("0.01")
            logger.error {
                "[$exchangeId] STRANDED LONG ($reason) — auto-unwind ON: " +
                    "selling LONG ${boughtContract.strike}${boughtContract.type.ibkrCode} back @ \$$sellBack"
            }
            val unwindId = submitSingleLeg(boughtContract, "SELL", qty, sellBack, "UNWIND-LONG")
            return OrderSubmissionResult(
                status = SubmissionStatus.LIQUIDITY_FAILED,
                primaryOrderId = longOrderId,
                secondaryOrderId = unwindId.takeIf { it != 0 },
                message = "$reason — auto-unwound stranded LONG (sell order=$unwindId)",
            )
        }
        logger.error {
            "[$exchangeId] STRANDED LONG ($reason) — auto-unwind OFF: keeping LONG $longOrderId " +
                "(${boughtContract.strike}${boughtContract.type.ibkrCode}) open; flagging BROKEN_LONG_ONLY for manual handling"
        }
        return OrderSubmissionResult(
            status = SubmissionStatus.STRANDED_LONG,
            primaryOrderId = longOrderId,
            message = "$reason — protective LONG left open (no naked short); requires manual handling",
        )
    }

    /** Await a single leg's fill up to [legFillTimeoutMs]. Timeout or broker error counts as not-filled. */
    private suspend fun awaitLegFill(orderId: Int): OrderStatus {
        val deferred = registry.pendingOrderStatus[orderId] ?: return OrderStatus.CANCELLED
        return try {
            withTimeout(legFillTimeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            logger.warn { "[$exchangeId] Leg $orderId did not fill within ${legFillTimeoutMs}ms" }
            OrderStatus.PENDING
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "[$exchangeId] Leg $orderId fill failed (broker error) — treating as not filled" }
            OrderStatus.CANCELLED
        }
    }

    private fun cancelQuietly(orderId: Int) {
        // Our own abort cancel, not a broker rejection — mark it so the code-202 callback is
        // logged at DEBUG and no reject reason is stashed.
        registry.markSelfCancelled(orderId)
        runCatching { client.cancelOrder(orderId, com.ib.client.OrderCancel()) }
            .onFailure { e -> logger.warn(e) { "[$exchangeId] Failed to cancel order $orderId" } }
        // Abandoned leg — drop its fill deferred so pendingOrderStatus doesn't leak (E8).
        registry.pendingOrderStatus.remove(orderId)
    }

    override fun validateOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
    ): ValidationResult {
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
        val spreadWidth = (soldContract.strike - boughtContract.strike).abs().toDouble()
        if (spreadWidth < 1.0) {
            return ValidationResult(false, "EUREX may require minimum \$1.00 spread width")
        }
        return ValidationResult(true)
    }

    override fun notes(): String =
        "Leg-by-leg submission (LONG-first, both-legs-confirmed). Protective long fills before the short, " +
            "so the worst case is a paid-for long put, never a naked short."

    /** Submit a single leg as an individual LMT order. Returns the orderId, or 0 on failure. */
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
            // resolveConIdOrCached uses a 6s timeout > the cache's own 5s network timeout, so the
            // cache governs and cleans up its own in-flight state. (A sub-second timeout here cancels
            // the cache mid-lookup and used to orphan its in-flight deferred — see E3.)
            val conId =
                contractCache.resolveConIdOrCached(key)
                    ?: error("Contract not in cache and lookup timed out for $key")

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
            if (orderId == 0) {
                logger.error { "[$exchangeId] nextOrderId returned 0 for $legDescription leg — submission unavailable" }
                0
            } else {
                registry.pendingOrderStatus[orderId] = CompletableDeferred()

                logger.debug {
                    "[$exchangeId] Submitting $legDescription leg: $action ${contract.strike}${contract.type.ibkrCode} " +
                        "@ $limitPrice orderId=$orderId"
                }
                client.placeOrder(orderId, legContract, order)
                orderId
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[$exchangeId] Failed to submit $legDescription leg" }
            0
        }

    /** Marketable BUY price for the protective long: pay the ask (rounded up to the tick grid). */
    private fun longLegLimitPrice(
        legQuotes: LegQuotes?,
        netCredit: Money,
    ): BigDecimal =
        legQuotes
            ?.boughtAsk
            ?.ceilToOptionTick()
            ?.coerceAtLeast(BigDecimal("0.01"))
            ?: (netCredit.amount * longLegCreditPct).floorToOptionTick().coerceAtLeast(BigDecimal("0.01"))

    /** Marketable SELL price for the short: hit the bid (rounded down to the tick grid). */
    private fun shortLegLimitPrice(
        legQuotes: LegQuotes?,
        netCredit: Money,
    ): BigDecimal =
        legQuotes
            ?.soldBid
            ?.floorToOptionTick()
            ?: (netCredit.amount * shortLegCreditPct).floorToOptionTick()
}
