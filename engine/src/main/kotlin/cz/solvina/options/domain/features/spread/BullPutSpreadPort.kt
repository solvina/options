package cz.solvina.options.domain.features.spread

import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Symbol
import java.time.Instant
import java.util.UUID

interface BullPutSpreadPort {
    suspend fun save(spread: BullPutSpread): BullPutSpread

    suspend fun update(spread: BullPutSpread): BullPutSpread

    suspend fun findById(id: UUID): BullPutSpread?

    suspend fun findOpen(): List<BullPutSpread>

    suspend fun findAll(): List<BullPutSpread>

    suspend fun findPage(
        status: SpreadStatus?,
        page: Int,
        size: Int,
    ): SpreadPage

    suspend fun countByStatus(status: SpreadStatus): Long

    /** Spreads that actually FILLED (status outside [SpreadStatus.NOT_FILLED]) opened at/after [since]. */
    suspend fun countFilledSince(since: Instant): Long

    suspend fun findByStatus(status: SpreadStatus): List<BullPutSpread>

    suspend fun findBySymbolWithLock(symbol: Symbol): List<BullPutSpread>
}

data class SpreadPage(
    val content: List<BullPutSpread>,
    val totalElements: Long,
    val totalPages: Int,
    val page: Int,
    val size: Int,
)
