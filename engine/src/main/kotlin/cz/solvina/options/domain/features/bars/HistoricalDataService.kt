package cz.solvina.options.domain.features.bars

import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

enum class FetchJobStatus { RUNNING, DONE, FAILED }

data class FetchJob(
    val id: String,
    val symbols: List<Symbol>,
    val from: LocalDate,
    val to: LocalDate,
    val timeframe: Timeframe,
    val status: FetchJobStatus,
    val barsWritten: Int,
    val error: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
)

@Service
class HistoricalDataService(
    private val barStorePort: BarStorePort,
    private val equityHistoricalBarsPort: EquityHistoricalBarsPort,
) {
    private val jobs = ConcurrentHashMap<String, FetchJob>()
    private val scope = CoroutineScope(Dispatchers.IO)

    suspend fun getCoverage(
        symbols: List<Symbol>,
        from: LocalDate,
        to: LocalDate,
        timeframe: Timeframe = Timeframe.FIVE_MIN,
    ): Map<Symbol, Map<LocalDate, Int>> {
        val result = mutableMapOf<Symbol, Map<LocalDate, Int>>()
        for (symbol in symbols) {
            result[symbol] = barStorePort.coverageByDay(symbol, from, to, timeframe)
        }
        return result
    }

    /** Full (re)fetch of the whole range for each symbol. */
    fun startFetch(
        symbols: List<Symbol>,
        from: LocalDate,
        to: LocalDate,
        timeframe: Timeframe = Timeframe.FIVE_MIN,
    ): FetchJob =
        launchJob(symbols, from, to, timeframe) { symbol ->
            equityHistoricalBarsPort.fetch5MinBarsForRange(symbol, from, to, timeframe).size
        }

    /**
     * Ensures [from]..[to] is covered for each symbol at [timeframe], fetching only the missing
     * head/tail (extend history backward / forward) — the "type AAPL 1999-now and it downloads once"
     * path. Interior holes are left alone (mostly weekends/holidays with legitimately no bar). A
     * second call over an already-covered span is a no-op. Backtests call this before running.
     */
    fun ensureCoverage(
        symbols: List<Symbol>,
        from: LocalDate,
        to: LocalDate,
        timeframe: Timeframe = Timeframe.FIVE_MIN,
    ): FetchJob =
        launchJob(symbols, from, to, timeframe) { symbol ->
            var written = 0
            for ((gapFrom, gapTo) in missingRanges(symbol, from, to, timeframe)) {
                logger.info { "[${symbol.value}] ensureCoverage: fetching ${timeframe.label} gap $gapFrom..$gapTo" }
                written += equityHistoricalBarsPort.fetch5MinBarsForRange(symbol, gapFrom, gapTo, timeframe).size
            }
            if (written == 0) logger.info { "[${symbol.value}] ensureCoverage: ${timeframe.label} already covered $from..$to" }
            written
        }

    /** The head/tail date ranges not yet stored for [symbol] at [timeframe] within [from]..[to]. */
    private suspend fun missingRanges(
        symbol: Symbol,
        from: LocalDate,
        to: LocalDate,
        timeframe: Timeframe,
    ): List<Pair<LocalDate, LocalDate>> {
        val stored = barStorePort.coverageByDay(symbol, from, to, timeframe).filterValues { it > 0 }.keys
        if (stored.isEmpty()) return listOf(from to to)
        val earliest = stored.min()
        val latest = stored.max()
        val gaps = mutableListOf<Pair<LocalDate, LocalDate>>()
        if (from.isBefore(earliest)) gaps += from to earliest.minusDays(1)
        if (to.isAfter(latest)) gaps += latest.plusDays(1) to to
        return gaps
    }

    private fun launchJob(
        symbols: List<Symbol>,
        from: LocalDate,
        to: LocalDate,
        timeframe: Timeframe,
        perSymbol: suspend (Symbol) -> Int,
    ): FetchJob {
        val id = UUID.randomUUID().toString()
        val job =
            FetchJob(
                id = id,
                symbols = symbols,
                from = from,
                to = to,
                timeframe = timeframe,
                status = FetchJobStatus.RUNNING,
                barsWritten = 0,
                error = null,
                startedAt = Instant.now(),
                finishedAt = null,
            )
        jobs[id] = job
        scope.launch {
            var totalBars = 0
            var failure: String? = null
            for (symbol in symbols) {
                try {
                    totalBars += perSymbol(symbol)
                } catch (e: Exception) {
                    logger.warn { "[${symbol.value}] Fetch job $id failed: ${e.message}" }
                    failure = e.message
                }
            }
            jobs[id] =
                jobs[id]!!.copy(
                    status = if (failure != null && totalBars == 0) FetchJobStatus.FAILED else FetchJobStatus.DONE,
                    barsWritten = totalBars,
                    error = failure,
                    finishedAt = Instant.now(),
                )
        }
        return job
    }

    fun getJob(id: String): FetchJob? = jobs[id]

    fun listJobs(): List<FetchJob> = jobs.values.sortedByDescending { it.startedAt }
}
