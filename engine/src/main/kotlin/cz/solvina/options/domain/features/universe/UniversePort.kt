package cz.solvina.options.domain.features.universe

import cz.solvina.options.domain.models.Symbol

interface UniversePort {
    fun getWatchlist(): List<Symbol>

    fun getActiveSymbols(): List<Symbol>

    /** Returns true if the underlying exchange for [symbol] is currently within regular trading hours. */
    fun isMarketOpen(symbol: Symbol): Boolean

    suspend fun getAll(): List<InstrumentConfig>

    suspend fun get(symbol: Symbol): InstrumentConfig?

    suspend fun save(config: InstrumentConfig): InstrumentConfig

    suspend fun delete(symbol: Symbol)
}
