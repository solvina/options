package cz.solvina.options.domain.features.volatility

import cz.solvina.options.domain.models.Symbol
import java.time.Instant

/** A persisted IV-rank snapshot, used to warm the in-memory cache across restarts. */
data class StoredIvRank(
    val rank: Double,
    val currentIv: Double,
    val calculatedAt: Instant,
)

/**
 * Persistence for computed IV ranks so a restart can warm-load them instead of re-fetching 365-day
 * IV history for every symbol at once (which would otherwise burst against IBKR's historical pacing).
 */
interface IvRankStorePort {
    /** Load all persisted IV ranks (called once at startup to warm the in-memory cache). */
    fun loadAll(): Map<Symbol, StoredIvRank>

    /** Persist the latest computed IV rank for [symbol]. */
    suspend fun save(
        symbol: Symbol,
        value: StoredIvRank,
    )
}
