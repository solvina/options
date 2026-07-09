package cz.solvina.options.adapters.outbound.ibkr.order

import cz.solvina.options.domain.features.order.LegQuotes
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract

/**
 * Exchange-aware strategy for submitting spread orders.
 * Different exchanges have different combo/spread capabilities:
 * - US exchanges (CBOE, ISE): Native combo orders (atomic, guaranteed both-legs-or-none)
 * - EUREX: No native combo → must leg-in manually with position matching
 * - Asia exchanges: Varied support (future)
 */
interface OrderExecutionStrategy {
    /**
     * Get the exchange identifier this strategy handles
     */
    fun getExchangeId(): String

    /**
     * Submit a credit spread order using this exchange's native mechanics.
     *
     * - US exchanges: places one atomic combo, returns (comboOrderId, null) immediately.
     * - EUREX leg-by-leg: legs in protective-LONG-first and only returns SUCCESS once BOTH legs are
     *   confirmed filled — (shortLegOrderId, longLegOrderId). Never leaves a naked short.
     *
     * [legQuotes] supplies fresh per-leg NBBO so each leg can be priced from its own book; native
     * combo strategies ignore it and price off [netCredit].
     */
    suspend fun submitSpreadOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
        legQuotes: LegQuotes? = null,
    ): OrderSubmissionResult

    /**
     * Validate order eligibility before submission
     */
    fun validateOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
    ): ValidationResult

    /**
     * Exchange-specific notes (for logging/debugging)
     */
    fun notes(): String

    /**
     * True if this exchange can amend a live combo order's price in place (single atomic BAG).
     * Native-combo (US) strategies override to true; leg-by-leg (EUREX) keeps the default false and
     * reprices via cancel-and-replace.
     */
    fun supportsInPlaceModify(): Boolean = false

    /**
     * Amend the working combo order [existingOrderId] to rest at [newCredit] in place: re-`placeOrder`
     * under the SAME orderId (IBKR treats this as a modification), leaving the caller's existing fill
     * watcher intact. Only implemented by strategies where [supportsInPlaceModify] is true.
     */
    suspend fun modifySpreadPrice(
        existingOrderId: Int,
        soldContract: OptionContract,
        boughtContract: OptionContract,
        newCredit: Money,
        qty: Int,
    ): Unit = throw UnsupportedOperationException("$javaClass does not support in-place modify")
}

/**
 * Result of attempting to submit a spread order
 */
data class OrderSubmissionResult(
    val status: SubmissionStatus,
    val primaryOrderId: Int,
    val secondaryOrderId: Int? = null,
    val message: String = "",
    val requiresManualMatching: Boolean = false, // true for EUREX leg-by-leg
)

enum class SubmissionStatus {
    // Order(s) accepted. NOTE the semantics differ by strategy (E11): native combo returns SUCCESS
    // on SUBMISSION (the fill is awaited later via the returned order id); leg-by-leg returns SUCCESS
    // only once BOTH legs are confirmed FILLED. See submitSpreadOrder KDoc.
    SUCCESS,
    REJECTED, // Exchange rejected before submission
    LIQUIDITY_FAILED, // Insufficient liquidity for matching (EUREX leg-in)
    STRANDED_LONG, // Leg-by-leg: protective LONG filled, SHORT did not, auto-unwind off — long left open
    SYSTEM_ERROR, // Connectivity or system error
}

/**
 * Validation result before attempting submission
 */
data class ValidationResult(
    val isValid: Boolean,
    val reason: String = "",
)
