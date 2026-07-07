package cz.solvina.options.testutil

import cz.solvina.options.domain.features.spread.BearCallSpreadPort
import cz.solvina.options.domain.features.spread.model.BearCallSpread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Symbol
import java.time.Instant
import java.util.UUID

/** Minimal in-memory [BearCallSpreadPort] for tests with no database (empty unless seeded). */
class InMemoryBearCallSpreadPort : BearCallSpreadPort {
    private val store = mutableListOf<BearCallSpread>()

    override suspend fun save(spread: BearCallSpread): BearCallSpread {
        val persisted = if (spread.id == null) spread.copy(id = UUID.randomUUID()) else spread
        store.add(persisted)
        return persisted
    }

    override suspend fun update(spread: BearCallSpread): BearCallSpread {
        val idx = store.indexOfFirst { it.id == spread.id }
        require(idx >= 0) { "Not found: ${spread.id}" }
        store[idx] = spread
        return spread
    }

    override suspend fun findById(id: UUID): BearCallSpread? = store.firstOrNull { it.id == id }

    override suspend fun findOpen(): List<BearCallSpread> = store.filter { it.status == SpreadStatus.OPEN }

    override suspend fun findAll(): List<BearCallSpread> = store.toList()

    override suspend fun countByStatus(status: SpreadStatus): Long = store.count { it.status == status }.toLong()

    override suspend fun countFilledSince(since: Instant): Long =
        store.count { it.openedAt >= since && it.status !in SpreadStatus.NOT_FILLED }.toLong()

    override suspend fun findByStatus(status: SpreadStatus): List<BearCallSpread> = store.filter { it.status == status }

    override suspend fun findBySymbolWithLock(symbol: Symbol): List<BearCallSpread> = store.filter { it.symbol == symbol }
}
