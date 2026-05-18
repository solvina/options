package cz.solvina.options.domain.features.diagnostic

import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol

interface DiagnosticProbePort {
    suspend fun probeContractResolution(symbol: Symbol): SymbolHealthReport.ContractResolutionResult

    suspend fun probeOptionParams(
        symbol: Symbol,
        stockConId: Int,
    ): SymbolHealthReport.OptionParamsResult

    suspend fun probeHistoricalData(symbol: Symbol): SymbolHealthReport.HistoricalDataResult

    suspend fun probeSpot(symbol: Symbol): SymbolHealthReport.SpotResult

    suspend fun probeOptionSnapshot(contract: OptionContract): SymbolHealthReport.OptionMidSample

    suspend fun probeTickStream(
        symbol: Symbol,
        soldContract: OptionContract,
        boughtContract: OptionContract,
        windowMs: Long = 5_000L,
    ): SymbolHealthReport.TickStreamResult

    suspend fun probeAccount(): AccountHealthReport
}
