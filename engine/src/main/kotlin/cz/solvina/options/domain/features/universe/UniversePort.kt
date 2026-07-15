package cz.solvina.options.domain.features.universe

import cz.solvina.options.domain.models.Symbol

interface UniversePort {
    fun getWatchlist(): List<Symbol>

    /** Symbols enabled for the flag (intraday momentum) strategy. */
    fun getFlagWatchlist(): List<Symbol>

    fun getActiveSymbols(): List<Symbol>

    /**
     * GICS sector label for [symbol] (e.g. "Information Technology"), or null when unknown.
     * Backs the per-sector open-spread cap. Defaults to null; the persistence adapter overrides it
     * with the cached universe value so unit-test fakes need no sector wiring.
     */
    fun sectorOf(symbol: Symbol): String? = null

    /** Returns true if the underlying exchange for [symbol] is currently within regular trading hours. */
    fun isMarketOpen(symbol: Symbol): Boolean

    /** Returns the exchange schedule (timezone, open/close times, session label) for [symbol]. */
    fun getMarketSchedule(symbol: Symbol): MarketSchedule

    suspend fun getAll(): List<InstrumentConfig>

    suspend fun get(symbol: Symbol): InstrumentConfig?

    suspend fun save(config: InstrumentConfig): InstrumentConfig

    suspend fun delete(symbol: Symbol)
}
