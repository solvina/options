package cz.solvina.options.adapters.outbound.ibkr.cache

import cz.solvina.options.domain.models.Symbol

/**
 * Persistence for option-chain params (expirations / strikes / venue), so a restart can warm-load
 * them instead of re-issuing reqSecDefOptParams for every symbol at the open. Mirrors the IV-rank
 * store — the same "slow reference data survives restarts" pattern. Kept in the adapter layer because
 * [OptionParams] is an IBKR-shaped model, not a domain concept.
 */
interface OptionParamsStorePort {
    /** Load all persisted option params (called once at startup to warm the in-memory cache). */
    fun loadAll(): Map<Symbol, OptionParams>

    /** Persist the latest fetched option params for [symbol]. */
    suspend fun save(
        symbol: Symbol,
        params: OptionParams,
    )
}
