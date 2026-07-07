package cz.solvina.options.domain.features.execution

import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.spread.model.StrategyId
import java.math.BigDecimal

/**
 * Strategy-specific persistence for the otherwise generic trade-execution loop.
 *
 * [TradeExecutionService] runs one order-execution loop (submit, ladder, drift, fill) for any 2-leg
 * spread and delegates the spread build + status writes to the writer resolved by
 * [TradeExecutionRequest.strategyId] — so the loop never references a concrete spread type, and a new
 * strategy plugs in by adding a writer. Each method returns the persisted [Spread] so the loop can
 * thread it forward.
 */
interface SpreadEntryWriter {
    val strategyId: StrategyId

    /** Persist the initial PENDING spread (combo orderId not yet known) and return it. */
    suspend fun persistPending(
        request: TradeExecutionRequest,
        credit: BigDecimal,
    ): Spread

    /** Stamp the combo orderId on both legs and record the submitted credit. */
    suspend fun stampOrderIds(
        spread: Spread,
        orderId: Int,
        credit: BigDecimal,
    ): Spread

    /**
     * Mark OPEN at the net (post-fee) fill credit, with the filled combo orderId on both legs.
     * [entryMid] is the spread's fair value (mid) at fill time — persisted so exit thresholds can
     * be computed off fair value instead of a possibly-below-mid fill credit; null when no fresh
     * tick was available at submission.
     */
    suspend fun markFilled(
        spread: Spread,
        orderId: Int,
        netCredit: BigDecimal,
        entryMid: BigDecimal?,
    ): Spread

    /** Leg-by-leg: the protective long filled but the short did not — record for manual handling. */
    suspend fun markBrokenLongOnly(
        spread: Spread,
        longOrderId: Int,
        reason: String,
    ): Spread

    /** Terminal / non-fill status transition (rejected, timed out, market moved, etc.). */
    suspend fun markStatus(
        spread: Spread,
        status: SpreadStatus,
        closeReason: String,
    ): Spread
}
