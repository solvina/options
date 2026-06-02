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

    suspend fun getCoverage(symbols: List<Symbol>, from: LocalDate, to: LocalDate): Map<Symbol, Map<LocalDate, Int>> {
        val result = mutableMapOf<Symbol, Map<LocalDate, Int>>()
        for (symbol in symbols) {
            result[symbol] = barStorePort.coverageByDay(symbol, from, to)
        }
        return result
    }

    fun startFetch(symbols: List<Symbol>, from: LocalDate, to: LocalDate): FetchJob {
        val id = UUID.randomUUID().toString()
        val job = FetchJob(
            id = id,
            symbols = symbols,
            from = from,
            to = to,
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
                    val bars = equityHistoricalBarsPort.fetch5MinBarsForRange(symbol, from, to)
                    totalBars += bars.size
                    logger.info { "[${symbol.value}] Fetch job $id: wrote ${bars.size} bars" }
                } catch (e: Exception) {
                    logger.warn { "[${symbol.value}] Fetch job $id failed: ${e.message}" }
                    failure = e.message
                }
            }
            jobs[id] = jobs[id]!!.copy(
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
