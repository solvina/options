package cz.solvina.options.adapters.outbound.ibkr.order

import cz.solvina.options.domain.features.account.PositionsPort
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
    private val positionsPort: PositionsPort,
) {
    /**
     * Verify that both legs of a spread actually filled in the account
     *
     * @param soldContract SHORT leg
     * @param boughtContract LONG leg
     * @param qty Expected quantity
     * @param timeoutMs How long to wait for verification (default 15s — the account position feed
     *   can lag order-level fill confirmations)
     * @return true if both legs found with correct quantities and directions
     */
    suspend fun verifyBothLegsFilled(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        qty: Int,
        timeoutMs: Long = 15_000,
    ): VerificationResult {
        val startTime = Instant.now()
        val matchKey = "${soldContract.symbol}-${soldContract.strike}-${boughtContract.strike}"

        while (Instant.now().toEpochMilli() - startTime.toEpochMilli() < timeoutMs) {
            try {
                // Query account positions to check for filled legs
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
                    return VerificationResult(
                        success = true,
                        shortLegFound = true,
                        longLegFound = true,
                        message = "Both legs verified in account",
                    )
                }

                // Not ready yet, wait a moment and retry
                delay(500)
            } catch (e: Exception) {
                logger.warn(e) { "Error querying positions for reconciliation: $matchKey" }
                // Continue retrying on error
                delay(500)
            }
        }

        // Timeout: one or both legs not found. Do NOT cancel orders or remove their fill deferreds
        // here — this method only runs after the leg-by-leg strategy confirmed BOTH legs FILLED at
        // the order level, so a timeout means the account position feed is lagging, not that the
        // orders are dangling. The old cleanup deleted the SHORT leg's completed fill deferred,
        // which made the execution loop's awaitFill see CANCELLED and record a real, filled spread
        // as CLOSED_REJECTED — exactly the failure the caller's trust-order-level-fills path exists
        // to prevent.
        logger.warn { "✗ Position reconciliation FAILED (timeout): $matchKey" }

        val finalShortLegFound =
            runCatching {
                checkPosition(soldContract.symbol.value, soldContract.strike, -qty, soldContract.type)
            }.getOrElse { false }

        val finalLongLegFound =
            runCatching {
                checkPosition(boughtContract.symbol.value, boughtContract.strike, qty, boughtContract.type)
            }.getOrElse { false }

        return VerificationResult(
            success = false,
            shortLegFound = finalShortLegFound,
            longLegFound = finalLongLegFound,
            message = "Timeout waiting for position reconciliation (possible broken spread)",
        )
    }

    /**
     * Check if a position exists in the account with expected quantity/direction
     * Queries account positions and matches by symbol, strike, and option type
     */
    private suspend fun checkPosition(
        symbol: String,
        strike: BigDecimal,
        expectedQty: Int,
        optionType: OptionType,
    ): Boolean {
        return try {
            val positions = positionsPort.getPositions()

            // Find position matching symbol, strike, and option type
            // BigDecimal equality via `==` is scale-sensitive (445 != 445.0) — compareTo is the
            // correct comparison for values that may arrive from IBKR with a different scale.
            val position =
                positions.firstOrNull { pos ->
                    pos.symbol == symbol &&
                        pos.strike?.compareTo(strike) == 0 &&
                        pos.optionRight == optionType.ibkrCode &&
                        pos.secType == "OPT"
                }

            if (position == null) {
                logger.debug { "Position not found: $symbol $strike ${optionType.ibkrCode}" }
                return false
            }

            // Check if quantity matches (allowing small tolerance for rounding)
            val quantity = position.quantity.toInt()
            val matches = quantity == expectedQty

            if (matches) {
                logger.debug { "Position verified: $symbol $strike ${optionType.ibkrCode} qty=$quantity" }
            } else {
                logger.debug {
                    "Position quantity mismatch: $symbol $strike ${optionType.ibkrCode} " +
                        "expected=$expectedQty, actual=$quantity"
                }
            }

            matches
        } catch (e: Exception) {
            logger.warn(e) { "Error checking position: $symbol $strike ${optionType.ibkrCode}" }
            false
        }
    }
}

/**
 * Result of verifying both legs filled
 */
data class VerificationResult(
    val success: Boolean,
    val shortLegFound: Boolean,
    val longLegFound: Boolean,
    val message: String,
)
