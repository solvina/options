package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.FetchJob
import cz.solvina.options.domain.features.bars.FetchJobStatus
import cz.solvina.options.domain.features.bars.HistoricalDataService
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Symbol
import `cz.solvina.options.historical`.api.HistoricalApi
import `cz.solvina.options.historical`.dto.FetchJobDto
import `cz.solvina.options.historical`.dto.FetchRequestDto
import `cz.solvina.options.historical`.dto.SeriesSummaryDto
import `cz.solvina.options.historical`.dto.SymbolCoverageDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

@RestController
@RequestMapping
class HistoricalApiImpl(
    private val historicalDataService: HistoricalDataService,
    private val universePort: UniversePort,
    private val barStorePort: BarStorePort,
) : HistoricalApi {
    override fun getHistoricalSummary(): ResponseEntity<Flow<SeriesSummaryDto>> {
        val items: Flow<SeriesSummaryDto> =
            flow {
                barStorePort.seriesSummary().forEach {
                    emit(
                        SeriesSummaryDto(
                            symbol = it.symbol,
                            interval = it.interval,
                            firstBar = OffsetDateTime.ofInstant(it.firstBar, ZoneOffset.UTC),
                            lastBar = OffsetDateTime.ofInstant(it.lastBar, ZoneOffset.UTC),
                            barCount = it.barCount,
                        ),
                    )
                }
            }
        return ResponseEntity.ok(items)
    }

    override fun getHistoricalCoverage(
        from: LocalDate,
        to: LocalDate,
        symbols: String?,
        timeframe: String?,
    ): ResponseEntity<Flow<SymbolCoverageDto>> {
        val symbolList = resolveSymbols(symbols)
        val tf = Timeframe.fromLabel(timeframe ?: Timeframe.FIVE_MIN.label)
        val items: Flow<SymbolCoverageDto> =
            flow {
                val coverage = historicalDataService.getCoverage(symbolList, from, to, tf)
                coverage.forEach { (symbol, dayMap) ->
                    emit(
                        SymbolCoverageDto(
                            symbol = symbol.value,
                            days = dayMap.entries.associate { (date, count) -> date.toString() to count },
                        ),
                    )
                }
            }
        return ResponseEntity.ok(items)
    }

    override suspend fun startHistoricalFetch(fetchRequestDto: FetchRequestDto): ResponseEntity<FetchJobDto> {
        val symbolList =
            if (fetchRequestDto.symbols.isNullOrEmpty()) {
                universePort.getWatchlist()
            } else {
                fetchRequestDto.symbols!!.map { Symbol(it.trim().uppercase()) }
            }
        val timeframe = Timeframe.fromLabel(fetchRequestDto.timeframe ?: Timeframe.FIVE_MIN.label)
        val job =
            if (fetchRequestDto.ensure == true) {
                historicalDataService.ensureCoverage(
                    symbols = symbolList,
                    from = fetchRequestDto.from,
                    to = fetchRequestDto.to,
                    timeframe = timeframe,
                )
            } else {
                historicalDataService.startFetch(
                    symbols = symbolList,
                    from = fetchRequestDto.from,
                    to = fetchRequestDto.to,
                    timeframe = timeframe,
                )
            }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job.toDto())
    }

    override fun listFetchJobs(): ResponseEntity<Flow<FetchJobDto>> {
        val jobs: Flow<FetchJobDto> =
            flow {
                historicalDataService.listJobs().forEach { emit(it.toDto()) }
            }
        return ResponseEntity.ok(jobs)
    }

    override suspend fun getFetchJob(id: String): ResponseEntity<FetchJobDto> {
        val job = historicalDataService.getJob(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(job.toDto())
    }

    private fun resolveSymbols(param: String?): List<Symbol> =
        if (param.isNullOrBlank()) {
            universePort.getWatchlist()
        } else {
            param.split(",").map { Symbol(it.trim().uppercase()) }
        }

    private fun FetchJob.toDto() =
        FetchJobDto(
            id = id,
            symbols = symbols.map { it.value },
            from = from,
            to = to,
            timeframe = timeframe.label,
            status =
                when (status) {
                    FetchJobStatus.RUNNING -> FetchJobDto.Status.RUNNING
                    FetchJobStatus.DONE -> FetchJobDto.Status.DONE
                    FetchJobStatus.FAILED -> FetchJobDto.Status.FAILED
                },
            barsWritten = barsWritten,
            error = error,
            startedAt = OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC),
            finishedAt = finishedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
        )
}
