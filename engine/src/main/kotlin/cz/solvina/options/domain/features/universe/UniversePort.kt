package cz.solvina.options.domain.features.universe

import cz.solvina.options.domain.models.Symbol

interface UniversePort {
    fun getWatchlist(): List<Symbol>

    fun getActiveSymbols(): List<Symbol>

    suspend fun getAll(): List<InstrumentConfig>

    suspend fun get(symbol: Symbol): InstrumentConfig?

    suspend fun save(config: InstrumentConfig): InstrumentConfig

    suspend fun delete(symbol: Symbol)
}
