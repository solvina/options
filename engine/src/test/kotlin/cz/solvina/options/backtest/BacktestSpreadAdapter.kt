package cz.solvina.options.backtest

import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import java.util.UUID

/**
 * In-memory spread store for backtesting.
 *
 * No persistence — all spreads live in a plain list for the duration
 * of a single [BacktestEngine] run. Supports the same query surface as
 * the production JPA adapter so [ScannerService] and [SpreadManagementService]
 * run unchanged.
 */
class BacktestSpreadAdapter : SpreadPort {
    private val store = mutableListOf<BullPutSpread>()

    override suspend fun save(spread: BullPutSpread): BullPutSpread {
        val persisted = if (spread.id == null) spread.copy(id = UUID.randomUUID()) else spread
        store.add(persisted)
        return persisted
    }

    override suspend fun update(spread: BullPutSpread): BullPutSpread {
        val idx = store.indexOfFirst { it.id == spread.id }
        require(idx >= 0) { "Spread ${spread.id} not found in backtest store" }
        store[idx] = spread
        return spread
    }

    override suspend fun findOpen(): List<BullPutSpread> = store.filter { it.status == SpreadStatus.OPEN }

    override suspend fun findAll(): List<BullPutSpread> = store.toList()

    override suspend fun countByStatus(status: SpreadStatus): Long = store.count { it.status == status }.toLong()

    override suspend fun findByStatus(status: SpreadStatus): List<BullPutSpread> = store.filter { it.status == status }
}
