package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Symbol
import `cz.solvina.options.universe`.api.UniverseApi
import `cz.solvina.options.universe`.dto.InstrumentConfigDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping
class UniverseApiImpl(
    private val universePort: UniversePort,
) : UniverseApi {
    override fun listUniverse(): ResponseEntity<Flow<InstrumentConfigDto>> {
        val items: Flow<InstrumentConfigDto> =
            flow {
                universePort.getAll().forEach { emit(it.toDto()) }
            }
        return ResponseEntity.ok(items)
    }

    override suspend fun saveInstrument(
        symbol: String,
        instrumentConfigDto: InstrumentConfigDto,
    ): ResponseEntity<InstrumentConfigDto> {
        val saved = universePort.save(instrumentConfigDto.toDomain(symbol))
        return ResponseEntity.ok(saved.toDto())
    }

    override suspend fun deleteInstrument(symbol: String): ResponseEntity<Unit> {
        universePort.delete(Symbol(symbol))
        return ResponseEntity.noContent().build()
    }

    override suspend fun toggleInstrument(symbol: String): ResponseEntity<InstrumentConfigDto> {
        val existing = universePort.get(Symbol(symbol)) ?: return ResponseEntity.notFound().build()
        val toggled = universePort.save(existing.copy(enabled = !existing.enabled))
        return ResponseEntity.ok(toggled.toDto())
    }

    override suspend fun toggleFlag(symbol: String): ResponseEntity<InstrumentConfigDto> {
        val existing = universePort.get(Symbol(symbol)) ?: return ResponseEntity.notFound().build()
        val toggled = universePort.save(existing.copy(flagEnabled = !existing.flagEnabled))
        return ResponseEntity.ok(toggled.toDto())
    }

    private fun InstrumentConfig.toDto() =
        InstrumentConfigDto(
            symbol = symbol.value,
            enabled = enabled,
            flagEnabled = flagEnabled,
            sector = sector,
            ivRankThreshold = ivRankThreshold,
            minDte = minDte,
            maxDte = maxDte,
            preferredDte = preferredDte,
            targetDelta = targetDelta,
            deltaMin = deltaMin,
            deltaMax = deltaMax,
            spreadWidthUsd = spreadWidthUsd,
            minCreditPerShare = minCreditPerShare,
            maxRiskPercent = maxRiskPercent,
            takeProfitPercent = takeProfitPercent,
            stopLossPercent = stopLossPercent,
            timeProfitDte = timeProfitDte,
            notes = notes,
        )

    private fun InstrumentConfigDto.toDomain(symbolOverride: String) =
        InstrumentConfig(
            symbol = Symbol(symbolOverride),
            enabled = enabled,
            flagEnabled = flagEnabled ?: false,
            sector = sector,
            ivRankThreshold = ivRankThreshold,
            minDte = minDte,
            maxDte = maxDte,
            preferredDte = preferredDte,
            targetDelta = targetDelta,
            deltaMin = deltaMin,
            deltaMax = deltaMax,
            spreadWidthUsd = spreadWidthUsd,
            minCreditPerShare = minCreditPerShare,
            maxRiskPercent = maxRiskPercent,
            takeProfitPercent = takeProfitPercent,
            stopLossPercent = stopLossPercent,
            timeProfitDte = timeProfitDte,
            notes = notes,
        )
}
