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

    // ensureCoverage memo: a param sweep calls ensureCoverage with the identical (symbol, range,
    // timeframe) thousands of times, and each verification is a full-range coverageByDay query —
    // the dominant Influx load once bar reads are cached. Remember spans verified covered for a
    // short TTL; bounded, so no eviction beyond dropping stale entries on overflow.
    private data class CoveredKey(
        val symbol: Symbol,
        val from: LocalDate,
        val to: LocalDate,
        val timeframe: Timeframe,
    )

    private val recentlyCovered = ConcurrentHashMap<CoveredKey, Long>()

    private fun isRecentlyCovered(key: CoveredKey): Boolean {
        val at = recentlyCovered[key] ?: return false
        if (System.currentTimeMillis() - at > COVERED_TTL_MS) {
            recentlyCovered.remove(key)
            return false
        }
        return true
    }

    private fun markCovered(key: CoveredKey) {
        if (recentlyCovered.size >= COVERED_MAX_ENTRIES) {
            val cutoff = System.currentTimeMillis() - COVERED_TTL_MS
            recentlyCovered.entries.removeIf { it.value < cutoff }
            if (recentlyCovered.size >= COVERED_MAX_ENTRIES) recentlyCovered.clear()
        }
        recentlyCovered[key] = System.currentTimeMillis()
    }

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
            val key = CoveredKey(symbol, from, to, timeframe)
            if (isRecentlyCovered(key)) return@launchJob 0
            var written = 0
            val gaps = missingRanges(symbol, from, to, timeframe)
            for ((gapFrom, gapTo) in gaps) {
                logger.info { "[${symbol.value}] ensureCoverage: fetching ${timeframe.label} gap $gapFrom..$gapTo" }
                written += equityHistoricalBarsPort.fetch5MinBarsForRange(symbol, gapFrom, gapTo, timeframe).size
            }
            if (written == 0) logger.info { "[${symbol.value}] ensureCoverage: ${timeframe.label} already covered $from..$to" }
            // Memoize when there is nothing left to try: no gaps, or the gaps yielded nothing
            // (backtest profile's noop fetch, delisted tail) — retrying within the TTL would only
            // re-run the same full-range coverage query. A fetch that DID write is not memoized,
            // so the next call re-verifies the now-changed coverage.
            if (gaps.isEmpty() || written == 0) markCovered(key)
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
        // A gap of only weekend days can never be filled — asking IBKR for it costs a silent
        // 45s chunk timeout per symbol on every rerun (covered-universe reruns burned hours on it).
        return gaps.filterNot { (gapFrom, gapTo) -> onlyWeekend(gapFrom, gapTo) }
    }

    private fun onlyWeekend(
        from: LocalDate,
        to: LocalDate,
    ): Boolean =
        from.datesUntil(to.plusDays(1)).allMatch {
            it.dayOfWeek == java.time.DayOfWeek.SATURDAY || it.dayOfWeek == java.time.DayOfWeek.SUNDAY
        }

    private fun launchJob(
        symbols: List<Symbol>,
        from: LocalDate,
        to: LocalDate,
        timeframe: Timeframe,
        perSymbol: suspend (Symbol) -> Int,
    ): FetchJob {
        val id = UUID.randomUUID().toString()
        // Sweeps create one job per request; drop the oldest finished ones so the map stays bounded.
        if (jobs.size > MAX_RETAINED_JOBS) {
            jobs.values
                .filter { it.status != FetchJobStatus.RUNNING }
                .sortedBy { it.startedAt }
                .take(jobs.size - MAX_RETAINED_JOBS)
                .forEach { jobs.remove(it.id) }
        }
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

    companion object {
        private const val COVERED_TTL_MS = 10 * 60 * 1000L
        private const val COVERED_MAX_ENTRIES = 5_000
        private const val MAX_RETAINED_JOBS = 2_000
    }
}
