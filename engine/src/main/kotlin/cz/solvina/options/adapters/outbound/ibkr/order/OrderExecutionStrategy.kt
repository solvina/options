package cz.solvina.options.adapters.outbound.ibkr.order

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
     * Submit a credit spread order using this exchange's native mechanics
     *
     * @return Pair<primaryOrderId, secondaryOrderId?>
     *   - US exchanges: (comboOrderId, null)
     *   - EUREX: (shortLegOrderId, longLegOrderId)
     */
    suspend fun submitSpreadOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
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
    SUCCESS, // Order(s) submitted successfully
    REJECTED, // Exchange rejected before submission
    LIQUIDITY_FAILED, // Insufficient liquidity for matching (EUREX leg-in)
    SYSTEM_ERROR, // Connectivity or system error
}

/**
 * Validation result before attempting submission
 */
data class ValidationResult(
    val isValid: Boolean,
    val reason: String = "",
)
