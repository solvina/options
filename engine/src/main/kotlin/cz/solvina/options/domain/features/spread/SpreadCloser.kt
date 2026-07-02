package cz.solvina.options.domain.features.spread

import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.spread.model.StrategyId
import java.math.BigDecimal
import java.util.UUID

/**
 * Strategy-specific persistence for the generic [SpreadManagementService] exit loop. The management
 * loop runs the shared TP/SL/DTE + strategy-exit logic over the [Spread] interface and delegates the
 * load + status/value writes to the closer resolved by [StrategyId] — so it never references a
 * concrete spread type. The mirror of the execution-side `SpreadEntryWriter`.
 */
interface SpreadCloser {
    val strategyId: StrategyId

    suspend fun findById(id: UUID): Spread?

    suspend fun openSpreads(): List<Spread>

    suspend fun closingSpreads(): List<Spread>

    /** Persist the latest observed spread value (no status change). */
    suspend fun recordLastValue(
        spread: Spread,
        value: BigDecimal,
    ): Spread

    /** Mark CLOSING (close orders in flight); [intendedStatus] is recorded as the close reason. */
    suspend fun markClosing(
        spread: Spread,
        intendedStatus: SpreadStatus,
        closePrice: BigDecimal,
    ): Spread

    /** Final close: terminal [status] + close price + exit context (underlying / IV at exit). */
    suspend fun close(
        spread: Spread,
        status: SpreadStatus,
        closeReason: String,
        closePrice: BigDecimal,
        underlyingAtExit: BigDecimal?,
        ivAtExit: BigDecimal?,
    ): Spread
}
