package cz.solvina.options.domain.features.spread

import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadStatus

interface SpreadPort {
    suspend fun save(spread: BullPutSpread): BullPutSpread

    suspend fun update(spread: BullPutSpread): BullPutSpread

    suspend fun findOpen(): List<BullPutSpread>

    suspend fun findAll(): List<BullPutSpread>

    suspend fun countByStatus(status: SpreadStatus): Long

    suspend fun findByStatus(status: SpreadStatus): List<BullPutSpread>
}
