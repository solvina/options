package cz.solvina.options.adapters.outbound.ibkr.order

import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Reconciliation service for non-atomic leg-by-leg orders (EUREX, etc.)
 *
 * Since these exchanges don't support atomic combo orders, we must:
 * 1. Submit legs individually
 * 2. Monitor fills separately
 * 3. Verify BOTH legs filled before marking as "ENTRY"
 * 4. Cancel and reconcile if only one leg filled
 *
 * This prevents the broken-spread problem where only one leg fills.
 */
@Component
class PositionReconciliationService(
    private val ibkrClient: com.ib.client.EClientSocket,
) {
    // Track pending leg-by-leg orders awaiting reconciliation
    private val pendingMatches = mutableMapOf<String, PendingLegMatch>()

    /**
     * Register a leg-by-leg order submission for reconciliation
     *
     * @param shortOrderId ID of the SHORT leg order
     * @param longOrderId ID of the LONG leg order
     * @param soldContract SHORT leg contract details
     * @param boughtContract LONG leg contract details
     * @param qty Number of contracts
     */
    fun registerPendingMatch(
        shortOrderId: Int,
        longOrderId: Int,
        soldContract: OptionContract,
        boughtContract: OptionContract,
        qty: Int,
    ) {
        val matchKey = "${soldContract.symbol}-${soldContract.strike}-${boughtContract.strike}"

        pendingMatches[matchKey] =
            PendingLegMatch(
                shortOrderId = shortOrderId,
                longOrderId = longOrderId,
                soldContract = soldContract,
                boughtContract = boughtContract,
                qty = qty,
                submittedAt = Instant.now(),
            )

        logger.info { "Registered pending match: $matchKey (orders: SHORT=$shortOrderId, LONG=$longOrderId)" }
    }

    /**
     * Verify that both legs of a spread actually filled in the account
     *
     * @param soldContract SHORT leg
     * @param boughtContract LONG leg
     * @param qty Expected quantity
     * @param timeoutMs How long to wait for verification (default 5s)
     * @return true if both legs found with correct quantities and directions
     */
    suspend fun verifyBothLegsFilled(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        qty: Int,
        timeoutMs: Long = 5000,
    ): VerificationResult {
        val startTime = Instant.now()
        val matchKey = "${soldContract.symbol}-${soldContract.strike}-${boughtContract.strike}"

        while (Instant.now().toEpochMilli() - startTime.toEpochMilli() < timeoutMs) {
            // Query account positions to check for filled legs
            // In real implementation, this would query IbkrPositionsAdapter

            // Check SHORT leg: should have -qty position
            val shortLegFound =
                checkPosition(
                    symbol = soldContract.symbol.value,
                    strike = soldContract.strike,
                    expectedQty = -qty, // SHORT = negative
                    optionType = soldContract.type,
                )

            // Check LONG leg: should have +qty position
            val longLegFound =
                checkPosition(
                    symbol = boughtContract.symbol.value,
                    strike = boughtContract.strike,
                    expectedQty = qty, // LONG = positive
                    optionType = boughtContract.type,
                )

            if (shortLegFound && longLegFound) {
                logger.info { "✓ Position reconciliation successful: $matchKey" }
                pendingMatches.remove(matchKey)
                return VerificationResult(
                    success = true,
                    shortLegFound = true,
                    longLegFound = true,
                    message = "Both legs verified in account",
                )
            }

            // Not ready yet, wait a moment and retry
            delay(500)
        }

        // Timeout: one or both legs not found
        logger.warn { "✗ Position reconciliation FAILED (timeout): $matchKey" }

        return VerificationResult(
            success = false,
            shortLegFound = checkPosition(soldContract.symbol.value, soldContract.strike, -qty, soldContract.type),
            longLegFound = checkPosition(boughtContract.symbol.value, boughtContract.strike, qty, boughtContract.type),
            message = "Timeout waiting for position reconciliation (possible broken spread)",
        )
    }

    /**
     * Get information about pending matches (for monitoring/debugging)
     */
    fun getPendingMatches(): Map<String, PendingLegMatch> = pendingMatches.toMap()

    /**
     * Abandon a pending match and trigger cleanup
     */
    suspend fun abandonMatch(
        shortOrderId: Int,
        longOrderId: Int,
    ) {
        logger.warn { "Abandoning pending match: SHORT=$shortOrderId, LONG=$longOrderId" }

        // Cancel both orders to prevent orphaned positions
        ibkrClient.cancelOrder(shortOrderId, com.ib.client.OrderCancel())
        ibkrClient.cancelOrder(longOrderId, com.ib.client.OrderCancel())

        // Remove from pending
        pendingMatches.values.removeIf { match ->
            match.shortOrderId == shortOrderId && match.longOrderId == longOrderId
        }
    }

    /**
     * Check if a position exists in the account with expected quantity/direction
     * This is a stub - in real implementation would query IbkrPositionsAdapter
     */
    private fun checkPosition(
        symbol: String,
        strike: BigDecimal,
        expectedQty: Int,
        optionType: OptionType,
    ): Boolean {
        // STUB: In real implementation:
        // 1. Query account positions from IbkrPositionsAdapter
        // 2. Find position matching symbol, strike, type
        // 3. Check if quantity matches (within tolerance)
        // 4. Return true/false

        return false // Placeholder
    }
}

/**
 * Details of a leg-by-leg order awaiting verification
 */
data class PendingLegMatch(
    val shortOrderId: Int,
    val longOrderId: Int,
    val soldContract: OptionContract,
    val boughtContract: OptionContract,
    val qty: Int,
    val submittedAt: Instant,
)

/**
 * Result of verifying both legs filled
 */
data class VerificationResult(
    val success: Boolean,
    val shortLegFound: Boolean,
    val longLegFound: Boolean,
    val message: String,
)
