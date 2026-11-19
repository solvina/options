package cz.solvina.options.domain.features.volatility

import cz.solvina.options.domain.models.Symbol
import java.time.Instant

/** A persisted IV-rank snapshot, used to warm the in-memory cache across restarts. */
data class StoredIvRank(
    val rank: Double,
    val currentIv: Double,
    val calculatedAt: Instant,
    /** Summary of the IV history the rank was computed from; null on rows from before v45. */
    val summary: IvSummary? = null,
)

/** Where today's IV index sits in its own history (IBKR OPTION_IMPLIED_VOLATILITY, 30-day index). */
data class IvSummary(
    val min: Double,
    val max: Double,
    /** Share of history days (0–100) with an IV below today's. */
    val percentile: Double,
    val sampleDays: Int,
    /** Last 30 daily IVs, oldest first. */
    val recent: List<Double>,
)

/**
 * Persistence for computed IV ranks so a restart can warm-load them instead of re-fetching 365-day
 * IV history for every symbol at once (which would otherwise burst against IBKR's historical pacing).
 */
interface IvRankStorePort {
    /** Load all persisted IV ranks (called once at startup to warm the in-memory cache). */
    fun loadAll(): Map<Symbol, StoredIvRank>

    /** The persisted IV rank for [symbol], or null. */
    fun find(symbol: Symbol): StoredIvRank? = loadAll()[symbol]

    /** Persist the latest computed IV rank for [symbol]. */
    suspend fun save(
        symbol: Symbol,
        value: StoredIvRank,
    )
}
