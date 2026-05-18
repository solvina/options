package cz.solvina.options.domain.features.diagnostic

import cz.solvina.options.domain.models.Symbol

interface DiagnosticPort {
    suspend fun probeSymbol(symbol: Symbol): SymbolHealthReport

    suspend fun probeAccount(): AccountHealthReport

    fun latestSymbolReports(): List<SymbolHealthReport>

    fun latestAccountReport(): AccountHealthReport?

    fun watchlistSymbols(): List<Symbol>
}
