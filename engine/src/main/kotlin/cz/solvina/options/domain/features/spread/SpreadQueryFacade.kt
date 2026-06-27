package cz.solvina.options.domain.features.spread

import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Symbol
import org.springframework.stereotype.Component

/**
 * Cross-strategy read aggregation over every spread port. This is the single place the shared
 * portfolio limit and entry dedup count across bull puts + bear calls, so the cap is "5 total",
 * not 5 per strategy. When a strategy has no positions its port simply contributes zero, so these
 * results are identical to the bull-put-only reads while bear call is dormant.
 */
@Component
class SpreadQueryFacade(
    private val bullPutPort: BullPutSpreadPort,
    private val bearCallPort: BearCallSpreadPort,
) {
    /** PENDING + OPEN + CLOSING across all strategies — the coarse scanner early-out count. */
    suspend fun activeSpreadCount(): Long =
        countActive(SpreadStatus.PENDING) + countActive(SpreadStatus.OPEN) + countActive(SpreadStatus.CLOSING)

    /** OPEN + CLOSING across all strategies — established positions; callers add their in-flight set. */
    suspend fun establishedSpreadCount(): Long = countActive(SpreadStatus.OPEN) + countActive(SpreadStatus.CLOSING)

    /** Symbols holding a PENDING/OPEN/CLOSING spread of any strategy (scanner entry dedup). */
    suspend fun symbolsWithActiveSpread(): Set<Symbol> = symbolsInStatuses(SpreadStatus.PENDING, SpreadStatus.OPEN, SpreadStatus.CLOSING)

    /** Symbols holding an OPEN/CLOSING spread of any strategy (pre-trade exposure check). */
    suspend fun symbolsWithOpenOrClosingSpread(): Set<Symbol> = symbolsInStatuses(SpreadStatus.OPEN, SpreadStatus.CLOSING)

    /** Symbols mid-close (CLOSING) of any strategy (pre-trade CLOSING freeze). */
    suspend fun symbolsWithClosingSpread(): Set<Symbol> = symbolsInStatuses(SpreadStatus.CLOSING)

    private suspend fun countActive(status: SpreadStatus): Long = bullPutPort.countByStatus(status) + bearCallPort.countByStatus(status)

    private suspend fun symbolsInStatuses(vararg statuses: SpreadStatus): Set<Symbol> {
        val symbols = mutableSetOf<Symbol>()
        for (status in statuses) {
            bullPutPort.findByStatus(status).forEach { symbols.add(it.symbol) }
            bearCallPort.findByStatus(status).forEach { symbols.add(it.symbol) }
        }
        return symbols
    }
}
