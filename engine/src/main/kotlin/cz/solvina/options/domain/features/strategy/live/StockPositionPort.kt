package cz.solvina.options.domain.features.strategy.live

import cz.solvina.options.domain.models.Symbol
import java.util.UUID

interface StockPositionPort {
    suspend fun save(position: StockPosition): StockPosition

    suspend fun findById(id: UUID): StockPosition?

    /** PENDING + OPEN — what the runner and the reconciler both need. */
    suspend fun findLive(): List<StockPosition>

    suspend fun findAll(): List<StockPosition>

    /** Guards the one-position-per-symbol-per-strategy rule before emitting a new entry. */
    suspend fun findLiveFor(
        strategyId: String,
        symbol: Symbol,
    ): StockPosition?
}
