package cz.solvina.options.domain.features.spread

import cz.solvina.options.domain.features.spread.model.BearCallSpread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Symbol
import java.time.Instant
import java.util.UUID

/**
 * Persistence port for bear call spreads (strategy #2). Mirrors [BullPutSpreadPort] for the methods
 * the scanner, management, and the portfolio cap need. Paged listing ([BullPutSpreadPort.findPage])
 * is intentionally omitted until the bear-call API/UI lands.
 */
interface BearCallSpreadPort {
    suspend fun save(spread: BearCallSpread): BearCallSpread

    suspend fun update(spread: BearCallSpread): BearCallSpread

    suspend fun findById(id: UUID): BearCallSpread?

    suspend fun findOpen(): List<BearCallSpread>

    suspend fun findAll(): List<BearCallSpread>

    suspend fun countByStatus(status: SpreadStatus): Long

    /** Spreads that actually FILLED (status outside [SpreadStatus.NOT_FILLED]) opened at/after [since]. */
    suspend fun countFilledSince(since: Instant): Long

    suspend fun findByStatus(status: SpreadStatus): List<BearCallSpread>

    suspend fun findBySymbolWithLock(symbol: Symbol): List<BearCallSpread>
}
