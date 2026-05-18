package cz.solvina.options.adapters.inbound.api

import `cz.solvina.options.diagnostic`.api.DiagnosticApi
import `cz.solvina.options.diagnostic`.dto.AccountHealthDto
import `cz.solvina.options.diagnostic`.dto.ContractResolutionDto
import `cz.solvina.options.diagnostic`.dto.DataHealthDto
import `cz.solvina.options.diagnostic`.dto.HistoricalDataDto
import `cz.solvina.options.diagnostic`.dto.OptionMidSampleDto
import `cz.solvina.options.diagnostic`.dto.OptionParamsDto
import `cz.solvina.options.diagnostic`.dto.SpotDto
import `cz.solvina.options.diagnostic`.dto.SymbolHealthDto
import `cz.solvina.options.diagnostic`.dto.TickStreamDto
import cz.solvina.options.domain.features.diagnostic.AccountHealthReport
import cz.solvina.options.domain.features.diagnostic.DiagnosticPort
import cz.solvina.options.domain.features.diagnostic.SymbolHealthReport
import cz.solvina.options.domain.models.Symbol
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset

@RestController
@RequestMapping
class DiagnosticApiImpl(
    private val diagnosticPort: DiagnosticPort,
) : DiagnosticApi {
    override suspend fun getDataHealth(): ResponseEntity<DataHealthDto> =
        ResponseEntity.ok(
            DataHealthDto(
                symbols = diagnosticPort.latestSymbolReports().map { it.toDto() },
                account = diagnosticPort.latestAccountReport()?.toDto(),
                watchlist = diagnosticPort.watchlistSymbols().map { it.value },
            ),
        )

    override suspend fun probeSymbol(symbol: String): ResponseEntity<SymbolHealthDto> {
        val report = diagnosticPort.probeSymbol(Symbol(symbol.uppercase()))
        return ResponseEntity.ok(report.toDto())
    }

    override suspend fun probeAccount(): ResponseEntity<AccountHealthDto> {
        val report = diagnosticPort.probeAccount()
        return ResponseEntity.ok(report.toDto())
    }

    private fun SymbolHealthReport.toDto() =
        SymbolHealthDto(
            symbol = symbol.value,
            probedAt = probedAt.atOffset(ZoneOffset.UTC),
            contractResolution =
                ContractResolutionDto(
                    stockConId = contractResolution.stockConId,
                    durationMs = contractResolution.durationMs,
                    error = contractResolution.error,
                ),
            optionParams =
                OptionParamsDto(
                    strikeCount = optionParams.strikeCount,
                    availableExpiries = optionParams.availableExpiries,
                    error = optionParams.error,
                ),
            historicalData =
                HistoricalDataDto(
                    barCount = historicalData.barCount,
                    ivPopulated = historicalData.ivPopulated,
                    currentIv = historicalData.currentIv?.toBigDecimal(),
                    ivRank = historicalData.ivRank?.toBigDecimal(),
                    error = historicalData.error,
                ),
            spot =
                SpotDto(
                    price = spot.price,
                    source = SpotDto.Source.forValue(spot.source.name),
                    durationMs = spot.durationMs,
                    error = spot.error,
                ),
            optionSamples =
                optionSamples.map { s ->
                    OptionMidSampleDto(
                        strike = s.strike,
                        expiry = s.expiry,
                        bid = s.bid.takeIf { !it.isNaN() }?.toBigDecimal(),
                        ask = s.ask.takeIf { !it.isNaN() }?.toBigDecimal(),
                        mid = s.mid,
                        delta = s.delta.takeIf { !it.isNaN() }?.toBigDecimal(),
                        impliedVol = s.impliedVol.takeIf { !it.isNaN() }?.toBigDecimal(),
                        source = OptionMidSampleDto.Source.forValue(s.source.name),
                        durationMs = s.durationMs,
                    )
                },
            tickStream =
                tickStream?.let { ts ->
                    TickStreamDto(
                        ticksReceived = ts.ticksReceived,
                        lastBid = ts.lastBid.takeIf { !it.isNaN() }?.toBigDecimal(),
                        lastAsk = ts.lastAsk.takeIf { !it.isNaN() }?.toBigDecimal(),
                        lastDelta = ts.lastDelta.takeIf { !it.isNaN() }?.toBigDecimal(),
                        windowMs = ts.windowMs,
                        error = ts.error,
                    )
                },
            errors = errors,
        )

    private fun AccountHealthReport.toDto() =
        AccountHealthDto(
            probedAt = probedAt.atOffset(ZoneOffset.UTC),
            netLiquidation = netLiquidation,
            availableFunds = availableFunds,
            accountError = accountError,
            positionCount = positionCount,
            positionsError = positionsError,
            openOrderCount = openOrderCount,
            openOrdersError = openOrdersError,
        )
}
