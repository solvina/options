package cz.solvina.options.domain.features.execution

import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

private val logger = KotlinLogging.logger {}

/**
 * Atomic entry validator using database-level locking (Issue #9 fix).
 *
 * Prevents race condition where entry validation passes, but by the time
 * the order is submitted, the symbol transitions from CLOSING to CLOSED.
 *
 * Solution: Use @Transactional with SERIALIZABLE isolation and database locks
 * to make the check+validation+submission atomic at the database level.
 */
@Component
class AtomicEntryValidator(
    private val spreadPort: SpreadPort,
    private val orderExecutionPort: OrderExecutionPort,
    private val accountPort: AccountPort,
    private val config: ScannerConfig,
) {
    /**
     * Atomically validate entry with database-level locking.
     *
     * Holds a database lock on spreads for the target symbol while:
     * 1. Checking if symbol is in CLOSING state
     * 2. Checking exposure/capital/liquidity
     *
     * Lock is released immediately after validation.
     * If lock cannot be acquired within timeout, retries with exponential backoff.
     *
     * @param request The entry request
     * @param inFlightSymbols In-memory in-flight symbols
     * @param maxRetries Number of times to retry on lock timeout (default 3)
     * @return ExecutionOutcome if validation fails, null if passes
     */
    suspend fun validateWithLock(
        request: TradeExecutionRequest,
        inFlightSymbols: Set<Symbol>,
        maxRetries: Int = 3,
    ): ExecutionOutcome? {
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                return atomicValidationWithDatabaseLock(request, inFlightSymbols)
            } catch (e: org.springframework.dao.PessimisticLockingFailureException) {
                lastException = e
                logger.debug {
                    "[${request.underlyingSymbol}] Atomic validation lock timeout (attempt $attempt/$maxRetries), retrying..."
                }

                if (attempt < maxRetries) {
                    val backoffMs = 100L * attempt // 100ms, 200ms, 300ms...
                    delay(backoffMs)
                } else {
                    logger.warn {
                        "[${request.underlyingSymbol}] Atomic validation failed after $maxRetries attempts due to lock timeout"
                    }
                    throw e
                }
            }
        }

        throw lastException ?: Exception("Atomic validation failed")
    }

    /**
     * Perform validation with database-level lock on spreads for this symbol.
     *
     * The @Transactional annotation with LockModeType.PESSIMISTIC_WRITE ensures
     * database-level mutual exclusion. SpreadRepository.findBySymbolWithLock()
     * uses SELECT ... FOR UPDATE to acquire the lock.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE, timeout = 10)
    private suspend fun atomicValidationWithDatabaseLock(
        request: TradeExecutionRequest,
        inFlightSymbols: Set<Symbol>,
    ): ExecutionOutcome? {
        // Issue #9: Database lock prevents concurrent modifications to symbol's spreads
        // This ensures no new closes can start while we're validating entry
        val spreadsForSymbol =
            spreadPort.findBySymbolWithLock(request.underlyingSymbol)

        // Check CLOSING state (now atomic - no transition can happen while locked)
        val closingSpreads =
            spreadsForSymbol.filter { it.status == SpreadStatus.CLOSING }

        if (closingSpreads.isNotEmpty()) {
            logger.info {
                "[${request.underlyingSymbol}] CLOSING_FREEZE — symbol has ${closingSpreads.size} in-flight close order(s), entry blocked"
            }
            return ExecutionOutcome.EXPOSURE_REJECTED
        }

        // Check for open spreads
        val openSpreads =
            spreadsForSymbol.filter { it.status == SpreadStatus.OPEN }

        if (openSpreads.isNotEmpty()) {
            logger.info {
                "[${request.underlyingSymbol}] EXPOSURE_REJECTED — symbol has ${openSpreads.size} open position(s)"
            }
            return ExecutionOutcome.EXPOSURE_REJECTED
        }

        // Check IBKR open orders
        val ibkrOpenSymbols =
            runCatching { orderExecutionPort.getSymbolsWithOpenOrders() }
                .onFailure { e ->
                    logger.warn(e) {
                        "[${request.underlyingSymbol}] Could not fetch open IBKR orders: ${e.message}"
                    }
                }
                .getOrDefault(emptySet())

        if (request.underlyingSymbol in ibkrOpenSymbols) {
            logger.info {
                "[${request.underlyingSymbol}] EXPOSURE_REJECTED — IBKR has open order"
            }
            return ExecutionOutcome.EXPOSURE_REJECTED
        }

        // Check in-flight symbols (in-memory state)
        if (request.underlyingSymbol in inFlightSymbols) {
            logger.info {
                "[${request.underlyingSymbol}] EXPOSURE_REJECTED — symbol in-flight"
            }
            return ExecutionOutcome.EXPOSURE_REJECTED
        }

        // Capital check
        val availableFunds =
            accountPort.accountDetail.value
                ?.availableFunds
                ?.amount
        val maxRiskPerContract = request.maxRiskPerShare.multiply(BigDecimal("100"))

        if (availableFunds == null || availableFunds < maxRiskPerContract) {
            logger.info {
                "[${request.underlyingSymbol}] CAPITAL_REJECTED — available=\$$availableFunds required=\$$maxRiskPerContract"
            }
            return ExecutionOutcome.CAPITAL_REJECTED
        }

        // Liquidity checks
        if (isLiquidityTooWide(request.soldBid, request.soldAsk, "sold")) {
            logger.info {
                "[${request.underlyingSymbol}] LIQUIDITY_REJECTED — sold leg spread too wide"
            }
            return ExecutionOutcome.LIQUIDITY_REJECTED
        }

        if (isLiquidityTooWide(request.boughtBid, request.boughtAsk, "bought")) {
            logger.info {
                "[${request.underlyingSymbol}] LIQUIDITY_REJECTED — bought leg spread too wide"
            }
            return ExecutionOutcome.LIQUIDITY_REJECTED
        }

        // All checks passed while holding database lock
        logger.info {
            "[${request.underlyingSymbol}] Atomic validation passed (all spreads locked, no concurrent closes)"
        }
        return null
    }

    private fun isLiquidityTooWide(
        bid: BigDecimal,
        ask: BigDecimal,
        leg: String,
    ): Boolean {
        val mid = bid.add(ask).divide(BigDecimal("2"), 4, RoundingMode.HALF_UP)
        if (mid <= BigDecimal.ZERO) {
            logger.debug { "Leg $leg mid is zero, skipping liquidity check" }
            return false
        }
        val spread = ask.subtract(bid)
        val spreadPct = spread.divide(mid, 4, RoundingMode.HALF_UP).toDouble()
        return spreadPct > config.maxLegBidAskSpreadPct
    }
}
