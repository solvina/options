package cz.solvina.options.domain.features.backtest.sweep

import cz.solvina.options.domain.features.backtest.BacktestEngine
import cz.solvina.options.domain.features.backtest.RuleBacktestStrategy
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.FetchJobStatus
import cz.solvina.options.domain.features.bars.HistoricalDataService
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.BufferedWriter
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val logger = KotlinLogging.logger {}

enum class SweepStatus { RUNNING, DONE, CANCELLED, FAILED, STOPPED }

/** One sweep axis: either an explicit value list or an inclusive min/max/step range. */
data class SweepAxis(
    val values: List<Any>, // BigDecimal or Boolean
) {
    companion object {
        fun range(
            min: BigDecimal,
            max: BigDecimal,
            step: BigDecimal,
        ): SweepAxis {
            require(step > BigDecimal.ZERO) { "sweep step must be > 0" }
            val out = mutableListOf<Any>()
            var v = min
            while (v <= max) {
                out += v
                v = v.add(step)
            }
            return SweepAxis(out)
        }
    }
}

data class SweepDefinition(
    val name: String,
    /** Fixed (non-swept) request: symbols, window, timeframe, capital, base strategy params. */
    val symbols: List<Symbol>,
    val from: LocalDate,
    val to: LocalDate,
    val timeframe: Timeframe,
    val initialCapital: BigDecimal,
    val baseParams: RuleBacktestStrategy.Params,
    /** Swept param name → axis, in column order. */
    val axes: LinkedHashMap<String, SweepAxis>,
    val parallelism: Int,
)

data class SweepCounts(
    val total: Long,
    val redundant: Long,
) {
    val toRun: Long get() = total - redundant
}

data class SweepJob(
    val id: String,
    val name: String,
    val status: SweepStatus,
    val totalCombos: Long,
    val redundantCombos: Long,
    /** Estimated combos this run will execute (survivors minus resumed rows). */
    val toRun: Long,
    val done: Long,
    val failed: Long,
    val resumedRows: Long,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val error: String?,
    val sweptParams: List<String>,
    val symbols: List<String>,
    val timeframe: String,
    /** results.csv exists and has data rows — the results page can open it. */
    val hasResults: Boolean,
)

@Service
class SweepService(
    private val barStore: BarStorePort,
    private val historicalData: HistoricalDataService,
    @Value("\${sweep.output-dir:sweeps}") outputDirProp: String,
    // Off on the RPi: a 100k-combo sweep would starve the trading engine for hours.
    @Value("\${sweep.enabled:true}") private val enabled: Boolean = true,
) {
    private val outputRoot: Path = Path.of(outputDirProp)
    private val jobs = ConcurrentHashMap<String, SweepJob>()
    private val running = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    companion object {
        /** Engine semantics: an ATR-multiple exit > 0 overrides the corresponding percent exit. */
        val ATR_OVERRIDES = mapOf("stopAtrMultiple" to "stopLossPct", "targetAtrMultiple" to "targetPct")

        val INT_PARAMS = setOf("rsiPeriod", "smaFastPeriod", "smaSlowPeriod", "atrPeriod", "maxOpenPositions")
        val BOOL_PARAMS = setOf("requireRsiRising", "requireUptrend")
        val DOUBLE_PARAMS =
            setOf(
                "rsiOversold",
                "supportProximityPct",
                "stopLossPct",
                "targetPct",
                "stopAtrMultiple",
                "targetAtrMultiple",
                "riskPerTrade",
                "riskPerTradePct",
            )
        val SWEEPABLE = INT_PARAMS + BOOL_PARAMS + DOUBLE_PARAMS

        val METRICS =
            listOf(
                "tradeCount",
                "winCount",
                "lossCount",
                "eodCount",
                "winRate",
                "totalPnl",
                "totalPnlPct",
                "profitFactor",
                "maxDrawdownPct",
                "annualizedReturnPct",
                "avgRMultiple",
                "avgWinR",
                "avgLossR",
                "finalCapital",
                "buyHoldPnlPct",
                "buyHoldAnnualizedPct",
            )

        /** CSV cell for a swept value — matches param-sweep.py's str(): "3" not "3.0", "2.6" not "2.60". */
        fun cell(v: Any?): String =
            when (v) {
                null -> ""
                is BigDecimal -> {
                    val s = v.stripTrailingZeros()
                    if (s.scale() <= 0) s.toBigIntegerExact().toString() else s.toPlainString()
                }
                else -> v.toString()
            }

        private fun positive(v: Any?): Boolean = v is BigDecimal && v > BigDecimal.ZERO

        private fun paramPositive(
            name: String,
            p: RuleBacktestStrategy.Params,
        ): Boolean =
            when (name) {
                "stopAtrMultiple" -> p.stopAtrMultiple > 0.0
                "targetAtrMultiple" -> p.targetAtrMultiple > 0.0
                else -> false
            }
    }

    /**
     * Exact combo counts without walking the grid: the ATR-override pairs touch disjoint axes,
     * so the grid factorizes into (pair subgrid) × (pair subgrid) × (remaining axes).
     */
    fun counts(def: SweepDefinition): SweepCounts {
        val axes = def.axes
        var total = 1L
        for (a in axes.values) total *= a.values.size
        val consumed = mutableSetOf<String>()
        var factor = 1L
        for ((atrP, pctP) in ATR_OVERRIDES) {
            val pctAxis = axes[pctP] ?: continue
            val atrAxis = axes[atrP]
            if (atrAxis != null) {
                val pos = atrAxis.values.count { positive(it) }.toLong()
                factor *= pos + (atrAxis.values.size - pos) * pctAxis.values.size
                consumed += atrP
                consumed += pctP
            } else {
                factor *= if (paramPositive(atrP, def.baseParams)) 1L else pctAxis.values.size.toLong()
                consumed += pctP
            }
        }
        var rest = 1L
        for ((p, a) in axes) if (p !in consumed) rest *= a.values.size
        return SweepCounts(total = total, redundant = total - factor * rest)
    }

    /** Lazy cartesian product over the axes, redundant (ATR-overridden) combos skipped. */
    fun comboSequence(def: SweepDefinition): Sequence<List<Any>> {
        val params = def.axes.keys.toList()
        val lists = def.axes.values.map { it.values }
        val idxOf = params.withIndex().associate { (i, p) -> p to i }
        // (atrIdx or null, atrFixedPositive, pctIdx, firstPctValue) per applicable override pair
        val checks =
            ATR_OVERRIDES.mapNotNull { (atrP, pctP) ->
                val pctI = idxOf[pctP] ?: return@mapNotNull null
                Quad(idxOf[atrP], paramPositive(atrP, def.baseParams), pctI, lists[pctI].first())
            }
        return sequence {
            if (lists.isEmpty() || lists.any { it.isEmpty() }) return@sequence
            val idx = IntArray(lists.size)
            while (true) {
                val combo = List(lists.size) { i -> lists[i][idx[i]] }
                val redundant =
                    checks.any { (atrI, atrFixedPos, pctI, pctFirst) ->
                        val atrPos = if (atrI != null) positive(combo[atrI]) else atrFixedPos
                        atrPos && combo[pctI] != pctFirst
                    }
                if (!redundant) yield(combo)
                var i = lists.size - 1
                while (i >= 0) {
                    if (++idx[i] < lists[i].size) break
                    idx[i] = 0
                    i--
                }
                if (i < 0) break
            }
        }
    }

    private data class Quad(
        val atrIdx: Int?,
        val atrFixedPositive: Boolean,
        val pctIdx: Int,
        val pctFirst: Any,
    )

    /** Applies one swept value onto the strategy params. Caller guarantees the name is SWEEPABLE. */
    fun applyParam(
        p: RuleBacktestStrategy.Params,
        name: String,
        value: Any,
    ): RuleBacktestStrategy.Params {
        val d = (value as? BigDecimal)?.toDouble() ?: 0.0
        val i = (value as? BigDecimal)?.toInt() ?: 0
        val b = value as? Boolean ?: false
        return when (name) {
            "rsiPeriod" -> p.copy(rsiPeriod = i)
            "rsiOversold" -> p.copy(rsiOversold = d)
            "requireRsiRising" -> p.copy(requireRsiRising = b)
            "smaFastPeriod" -> p.copy(smaFastPeriod = i)
            "smaSlowPeriod" -> p.copy(smaSlowPeriod = i)
            "requireUptrend" -> p.copy(requireUptrend = b)
            "supportProximityPct" -> p.copy(supportProximityPct = d)
            "stopLossPct" -> p.copy(stopLossPct = d)
            "targetPct" -> p.copy(targetPct = d)
            "atrPeriod" -> p.copy(atrPeriod = i)
            "stopAtrMultiple" -> p.copy(stopAtrMultiple = d)
            "targetAtrMultiple" -> p.copy(targetAtrMultiple = d)
            "riskPerTrade" -> p.copy(riskPerTrade = d)
            "riskPerTradePct" -> p.copy(riskPerTradePct = d)
            "maxOpenPositions" -> p.copy(maxOpenPositions = i)
            else -> error("not sweepable: $name")
        }
    }

    fun list(): List<SweepJob> {
        scanOutputDir()
        return jobs.values.sortedWith(
            compareByDescending<SweepJob> { it.status == SweepStatus.RUNNING }.thenByDescending { it.startedAt ?: Instant.EPOCH },
        )
    }

    fun get(id: String): SweepJob? {
        scanOutputDir()
        return jobs[id]
    }

    fun resultsPath(id: String): Path? = jobs[id]?.let { outputRoot.resolve(it.name).resolve("results.csv") }?.takeIf { it.exists() }

    fun configPath(id: String): Path? = jobs[id]?.let { outputRoot.resolve(it.name).resolve("config.json") }?.takeIf { it.exists() }

    /** Cancels a running sweep. Rows already written stay; starting the same name later resumes. */
    fun cancel(id: String): SweepJob? {
        running[id]?.cancel()
        return jobs[id]
    }

    /** Deletes a finished/cancelled sweep's registry entry and its output directory. */
    fun purge(id: String): Boolean {
        val job = jobs[id] ?: return false
        if (job.status == SweepStatus.RUNNING) return false
        val dir = outputRoot.resolve(job.name)
        if (dir.exists()) dir.toFile().deleteRecursively()
        jobs.remove(id)
        return true
    }

    fun start(
        def: SweepDefinition,
        configJson: String,
    ): SweepJob {
        require(enabled) { "sweeps are disabled on this instance (sweep.enabled=false) — run them on the backtest workstation" }
        require(def.axes.isNotEmpty()) { "sweep needs at least one axis" }
        for (p in def.axes.keys) require(p in SWEEPABLE) { "not a sweepable parameter: $p" }
        require(def.symbols.isNotEmpty()) { "symbols required" }
        scanOutputDir()
        val existing = jobs[def.name]
        require(existing?.status != SweepStatus.RUNNING) { "sweep '${def.name}' is already running" }

        val dir = outputRoot.resolve(def.name)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("config.json"), configJson)
        val resultsPath = dir.resolve("results.csv")
        val counts = counts(def)
        val sweptParams = def.axes.keys.toList()
        val doneKeys = loadDoneKeys(resultsPath, sweptParams)

        val job =
            SweepJob(
                id = def.name,
                name = def.name,
                status = SweepStatus.RUNNING,
                totalCombos = counts.total,
                redundantCombos = counts.redundant,
                toRun = (counts.toRun - doneKeys.size).coerceAtLeast(0),
                done = 0,
                failed = 0,
                resumedRows = doneKeys.size.toLong(),
                startedAt = Instant.now(),
                finishedAt = null,
                error = null,
                sweptParams = sweptParams,
                symbols = def.symbols.map { it.value },
                timeframe = def.timeframe.label,
                hasResults = doneKeys.isNotEmpty(),
            )
        jobs[job.id] = job
        running[job.id] = scope.launch { runSweep(def, job.id, resultsPath, dir.resolve("failures.csv"), doneKeys) }
        logger.info {
            "Sweep '${def.name}' started: ${counts.total} combos, ${counts.redundant} redundant, " +
                "${doneKeys.size} resumed, ${job.toRun} to run at parallelism ${def.parallelism}"
        }
        return job
    }

    private suspend fun runSweep(
        def: SweepDefinition,
        id: String,
        resultsPath: Path,
        failuresPath: Path,
        doneKeys: Set<List<String>>,
    ) {
        val sweptParams = def.axes.keys.toList()
        var results: BufferedWriter? = null
        var failures: BufferedWriter? = null
        var finalStatus = SweepStatus.DONE
        var error: String? = null
        try {
            ensureCoverageOnce(def)

            val writeHeader = !resultsPath.exists() || Files.size(resultsPath) == 0L
            val resultsW = Files.newBufferedWriter(resultsPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            val failuresW = Files.newBufferedWriter(failuresPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            results = resultsW
            failures = failuresW
            if (writeHeader) {
                resultsW.appendLine((sweptParams + METRICS).joinToString(","))
                resultsW.flush()
            }
            if (Files.size(failuresPath) == 0L) failuresW.appendLine((sweptParams + "error").joinToString(","))

            val writeMutex = Mutex()
            var done = 0L
            var failed = 0L
            var lastPublish = 0L
            val channel = Channel<List<Any>>(capacity = def.parallelism * 2)
            val workers = def.parallelism.coerceIn(1, 64)

            kotlinx.coroutines.coroutineScope {
                launch {
                    for (combo in comboSequence(def)) {
                        if (combo.map { cell(it) } in doneKeys) continue
                        channel.send(combo)
                    }
                    channel.close()
                }
                List(workers) {
                    launch {
                        for (combo in channel) {
                            var row = ""
                            var failure: String? = null
                            try {
                                val summary = runCombo(def, sweptParams, combo)
                                row = (combo.map { cell(it) } + summaryCells(summary)).joinToString(",")
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failure = e.message ?: e.javaClass.simpleName
                            }
                            writeMutex.withLock {
                                if (failure == null) {
                                    resultsW.appendLine(row)
                                } else {
                                    failed++
                                    failuresW.appendLine((combo.map { cell(it) } + csvEscape(failure)).joinToString(","))
                                }
                                done++
                                if (done - lastPublish >= 25) {
                                    lastPublish = done
                                    resultsW.flush()
                                    failuresW.flush()
                                    jobs[id] = jobs[id]!!.copy(done = done, failed = failed, hasResults = true)
                                }
                            }
                        }
                    }
                }.joinAll()
            }
            jobs[id] = jobs[id]!!.copy(done = done, failed = failed)
        } catch (e: kotlinx.coroutines.CancellationException) {
            finalStatus = SweepStatus.CANCELLED
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Sweep '$id' failed: ${e.message}" }
            finalStatus = SweepStatus.FAILED
            error = e.message
        } finally {
            runCatching {
                results?.flush()
                results?.close()
            }
            runCatching {
                failures?.flush()
                failures?.close()
            }
            running.remove(id)
            jobs[id]?.let {
                jobs[id] =
                    it.copy(
                        status = finalStatus,
                        finishedAt = Instant.now(),
                        error = error,
                        hasResults = resultsPath.exists() && Files.size(resultsPath) > 0L,
                    )
            }
            logger.info { "Sweep '$id' finished: ${jobs[id]?.status} (${jobs[id]?.done} done, ${jobs[id]?.failed} failed)" }
        }
    }

    private suspend fun runCombo(
        def: SweepDefinition,
        sweptParams: List<String>,
        combo: List<Any>,
    ): BacktestEngine.Summary {
        var params = def.baseParams
        combo.forEachIndexed { i, v -> params = applyParam(params, sweptParams[i], v) }
        RuleBacktestStrategy.validationError(params)?.let { throw IllegalArgumentException(it) }
        val warmupDays = warmupDays(def)
        val engine = BacktestEngine(barStore)
        val result =
            engine.run<RuleBacktestStrategy.RuleTrade>(
                BacktestEngine.Request(
                    symbols = def.symbols,
                    from = def.from,
                    to = def.to,
                    initialCapital = def.initialCapital,
                    maxOpenPositions = params.maxOpenPositions,
                    warmupDays = warmupDays,
                    holdOvernight = true,
                    timeframe = def.timeframe,
                ),
                RuleBacktestStrategy(params),
            )
        return result.summary
    }

    /**
     * Warmup must cover the slowest swept-or-fixed SMA; constant for the whole sweep so every
     * combo reads the identical (cached) bar range.
     */
    private fun warmupDays(def: SweepDefinition): Long {
        fun maxOf(
            name: String,
            base: Int,
        ): Int =
            def.axes[name]
                ?.values
                ?.filterIsInstance<BigDecimal>()
                ?.maxOfOrNull { it.toInt() }
                ?.coerceAtLeast(base) ?: base
        val slowest =
            maxOf(
                "smaSlowPeriod",
                def.baseParams.smaSlowPeriod,
            ).coerceAtLeast(maxOf("smaFastPeriod", def.baseParams.smaFastPeriod))
        return (slowest * 2L).coerceAtLeast(400L)
    }

    private suspend fun ensureCoverageOnce(def: SweepDefinition) {
        val job = historicalData.ensureCoverage(def.symbols, def.from.minusDays(warmupDays(def)), def.to, def.timeframe)
        var waitedMs = 0L
        while (historicalData.getJob(job.id)?.status == FetchJobStatus.RUNNING && waitedMs < 600_000L) {
            delay(200)
            waitedMs += 200
        }
    }

    private fun summaryCells(s: BacktestEngine.Summary): List<String> =
        listOf(
            s.tradeCount.toString(),
            s.winCount.toString(),
            s.lossCount.toString(),
            s.eodCount.toString(),
            s.winRate.toString(),
            cellBd(s.totalPnl),
            cellBd(s.totalPnlPct),
            cellBd(s.profitFactor),
            cellBd(s.maxDrawdownPct),
            cellBd(s.annualizedReturnPct),
            cellBd(s.avgRMultiple),
            cellBd(s.avgWinR),
            cellBd(s.avgLossR),
            cellBd(s.finalCapital),
            cellBd(s.buyHoldPnlPct),
            cellBd(s.buyHoldAnnualizedPct),
        )

    private fun cellBd(v: BigDecimal?): String = v?.toPlainString() ?: ""

    private fun csvEscape(v: String): String = '"' + v.replace("\"", "\"\"").replace('\n', ' ') + '"'

    /** Swept-value tuples already present in results.csv (resume, param-sweep.py compatible). */
    private fun loadDoneKeys(
        resultsPath: Path,
        sweptParams: List<String>,
    ): Set<List<String>> {
        if (!resultsPath.exists()) return emptySet()
        Files.newBufferedReader(resultsPath).use { r ->
            val header = r.readLine()?.split(",") ?: return emptySet()
            val idx = sweptParams.map { header.indexOf(it) }
            if (idx.any { it < 0 }) return emptySet() // different grid shape — no resume
            val keys = mutableSetOf<List<String>>()
            r.forEachLine { line ->
                val parts = line.split(",")
                keys += idx.map { parts.getOrElse(it) { "" } }
            }
            return keys
        }
    }

    /** Registers finished runs found on disk (previous engine runs, param-sweep.py runs). */
    private fun scanOutputDir() {
        if (!outputRoot.exists() || !outputRoot.isDirectory()) return
        for (dir in outputRoot.listDirectoryEntries()) {
            if (!dir.isDirectory()) continue
            val name = dir.name
            if (jobs.containsKey(name)) continue
            val results = dir.resolve("results.csv")
            if (!results.exists()) continue
            val sweptParams = runCatching { sweptParamsFromHeader(results) }.getOrDefault(emptyList())
            jobs[name] =
                SweepJob(
                    id = name,
                    name = name,
                    status = SweepStatus.STOPPED,
                    totalCombos = 0,
                    redundantCombos = 0,
                    toRun = 0,
                    done = countRows(results),
                    failed = 0,
                    resumedRows = 0,
                    startedAt = runCatching { Files.getLastModifiedTime(results).toInstant() }.getOrNull(),
                    finishedAt = null,
                    error = null,
                    sweptParams = sweptParams,
                    symbols = emptyList(),
                    timeframe = "",
                    hasResults = true,
                )
        }
    }

    private fun sweptParamsFromHeader(results: Path): List<String> {
        val header = Files.newBufferedReader(results).use { it.readLine() } ?: return emptyList()
        val cols = header.split(",")
        val metricStart = cols.indexOf("tradeCount")
        return if (metricStart > 0) cols.take(metricStart) else emptyList()
    }

    private fun countRows(results: Path): Long =
        runCatching {
            Files.newBufferedReader(results).use { r -> generateSequence { r.readLine() }.count().toLong() - 1 }
        }.getOrDefault(0L).coerceAtLeast(0L)
}
