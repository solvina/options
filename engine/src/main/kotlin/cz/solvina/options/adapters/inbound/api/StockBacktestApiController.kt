package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.backtest.BacktestEngine
import cz.solvina.options.domain.features.backtest.StrategyBacktestAdapter
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.FetchJobStatus
import cz.solvina.options.domain.features.bars.HistoricalDataService
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.strategy.ParamType
import cz.solvina.options.domain.features.strategy.StockStrategy
import cz.solvina.options.domain.features.strategy.StrategyParams
import cz.solvina.options.domain.features.strategy.StrategyRegistry
import cz.solvina.options.domain.features.strategy.StrategyTrade
import cz.solvina.options.domain.features.strategy.SupportBounceStrategy
import cz.solvina.options.domain.features.universe.SectorEtf
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.ceil

private val logger = KotlinLogging.logger {}

/**
 * Stock strategy backtest. Silently ensures the required bars are downloaded/cached at the chosen
 * timeframe (data-on-demand), then runs the requested [StockStrategy] through the shared
 * [BacktestEngine]. First run over a cold span downloads; later runs serve from the store.
 *
 * The strategy is chosen by id from the [StrategyRegistry] and configured from a free-form `params`
 * blob, so a new strategy needs no change here. Requests that name no strategy keep the original
 * flat `support_bounce` fields — the shape the UI and stored sweep definitions already send.
 *
 * NOTE on the path: the app runs under WebFlux base-path /options, and BOTH proxies (nginx and the
 * Vite dev server) rewrite the browser's /api/X to /options/X. Controllers must therefore map the
 * path WITHOUT the /api prefix — "/api/backtest" here produced /options/api/backtest, which no
 * proxy ever reached (every backtest endpoint 404'd from the UI).
 */
@RestController
@RequestMapping("/backtest")
class StockBacktestApiController(
    private val historicalData: HistoricalDataService,
    private val barStore: BarStorePort,
    private val universePort: UniversePort,
    private val strategies: StrategyRegistry,
) {
    data class StockBacktestRequest(
        val symbols: List<String>,
        val from: LocalDate,
        val to: LocalDate,
        val timeframe: String? = null, // "1d" (default) | "4h" | "5min"
        val initialCapital: BigDecimal? = null,
        /** Strategy id from [StrategyRegistry]; null → `support_bounce` with the flat fields below. */
        val strategy: String? = null,
        /** Strategy params by descriptor name; null → the strategy's own defaults. */
        val params: Map<String, Any?>? = null,
        /** Swing default: positions hold to stop/target instead of being liquidated at day end. */
        val holdOvernight: Boolean? = null,
        /** When set, exits trail this ×R below the running peak instead of using a fixed target. */
        val trailStopRMultiple: Double? = null,
        // Legacy flat support_bounce params (null → strategy defaults). Superseded by [params].
        val rsiPeriod: Int? = null,
        val rsiOversold: Double? = null,
        val requireRsiRising: Boolean? = null,
        val smaFastPeriod: Int? = null,
        val smaSlowPeriod: Int? = null,
        val requireUptrend: Boolean? = null,
        val supportProximityPct: Double? = null,
        val stopLossPct: Double? = null,
        val targetPct: Double? = null,
        val atrPeriod: Int? = null,
        val stopAtrMultiple: Double? = null,
        val targetAtrMultiple: Double? = null,
        val riskPerTrade: Double? = null,
        val riskPerTradePct: Double? = null,
        val maxOpenPositions: Int? = null,
        val maxLeverage: Double? = null,
    ) {
        /** Nulls fall back to [SupportBounceStrategy.Params] defaults. Shared with the sweep API. */
        fun toParams(): SupportBounceStrategy.Params {
            val d = SupportBounceStrategy.Params()
            return SupportBounceStrategy.Params(
                rsiPeriod = rsiPeriod ?: d.rsiPeriod,
                rsiOversold = rsiOversold ?: d.rsiOversold,
                requireRsiRising = requireRsiRising ?: d.requireRsiRising,
                smaFastPeriod = smaFastPeriod ?: d.smaFastPeriod,
                smaSlowPeriod = smaSlowPeriod ?: d.smaSlowPeriod,
                requireUptrend = requireUptrend ?: d.requireUptrend,
                supportProximityPct = supportProximityPct ?: d.supportProximityPct,
                stopLossPct = stopLossPct ?: d.stopLossPct,
                targetPct = targetPct ?: d.targetPct,
                atrPeriod = atrPeriod ?: d.atrPeriod,
                stopAtrMultiple = stopAtrMultiple ?: d.stopAtrMultiple,
                targetAtrMultiple = targetAtrMultiple ?: d.targetAtrMultiple,
                riskPerTrade = riskPerTrade ?: d.riskPerTrade,
                riskPerTradePct = riskPerTradePct ?: d.riskPerTradePct,
                maxOpenPositions = maxOpenPositions ?: d.maxOpenPositions,
                maxLeverage = maxLeverage ?: d.maxLeverage,
            )
        }

        /** The flat fields that were actually sent, as a params blob. Only meaningful for legacy requests. */
        fun flatOverrides(): Map<String, Any?> =
            buildMap {
                rsiPeriod?.let { put("rsiPeriod", it) }
                rsiOversold?.let { put("rsiOversold", it) }
                requireRsiRising?.let { put("requireRsiRising", it) }
                smaFastPeriod?.let { put("smaFastPeriod", it) }
                smaSlowPeriod?.let { put("smaSlowPeriod", it) }
                requireUptrend?.let { put("requireUptrend", it) }
                supportProximityPct?.let { put("supportProximityPct", it) }
                stopLossPct?.let { put("stopLossPct", it) }
                targetPct?.let { put("targetPct", it) }
                atrPeriod?.let { put("atrPeriod", it) }
                stopAtrMultiple?.let { put("stopAtrMultiple", it) }
                targetAtrMultiple?.let { put("targetAtrMultiple", it) }
                riskPerTrade?.let { put("riskPerTrade", it) }
                riskPerTradePct?.let { put("riskPerTradePct", it) }
                maxOpenPositions?.let { put("maxOpenPositions", it) }
                maxLeverage?.let { put("maxLeverage", it) }
            }
    }

    /** One strategy as the UI needs it: identity plus the form to render. */
    data class StrategyDto(
        val id: String,
        val displayName: String,
        val timeframes: List<String>,
        val warmupBars: Int,
        val requiresTicks: Boolean,
        val params: List<ParamDto>,
    )

    data class ParamDto(
        val name: String,
        val type: ParamType,
        val default: Any?,
        val min: Double?,
        val max: Double?,
        val group: String,
        val help: String?,
    )

    /** The strategy library — drives the strategy dropdown and its descriptor-generated form. */
    @GetMapping("/strategies")
    fun listStrategies(): ResponseEntity<List<StrategyDto>> =
        ResponseEntity.ok(
            strategies.all().map { s ->
                StrategyDto(
                    id = s.id,
                    displayName = s.displayName,
                    timeframes = s.inputs.timeframes.map { it.label },
                    warmupBars = s.inputs.warmupBars,
                    requiresTicks = s.inputs.requiresTicks,
                    params =
                        s.params.map {
                            ParamDto(it.name, it.type, it.default, it.min, it.max, it.group, it.help)
                        },
                )
            },
        )

    /**
     * Runs [req] and returns a `BacktestEngine.Result<StrategyTrade>`, or 400 with `{ "error": … }`.
     * The reason travels in the body because a rejected param is a user-fixable mistake and the UI
     * can only say "400" otherwise.
     */
    @PostMapping("/stock")
    suspend fun runStockBacktest(
        @RequestBody req: StockBacktestRequest,
    ): ResponseEntity<Any> {
        if (req.symbols.isEmpty()) return reject("at least one symbol is required")
        if (req.from.isAfter(req.to)) return reject("'from' must not be after 'to'")
        val timeframe = Timeframe.fromLabel(req.timeframe ?: Timeframe.DAILY.label)
        val symbols = req.symbols.map { Symbol(it.trim().uppercase()) }

        val template =
            strategies.find(req.strategy ?: SupportBounceStrategy.ID)
                ?: return reject("unknown strategy '${req.strategy}'")
        // A named strategy takes its params blob; a request without one keeps the flat legacy fields.
        val overrides = req.params ?: req.flatOverrides()
        val resolved =
            try {
                StrategyParams.resolve(template.params, overrides)
            } catch (e: IllegalArgumentException) {
                return reject("${template.id}: ${e.message}")
            }

        // Server-side param validation: a zero/negative period silently yields 0 trades (NaN-free
        // but meaningless), so reject here for EVERY client — browser input constraints only
        // protect the React form.
        if (req.initialCapital != null && req.initialCapital <= BigDecimal.ZERO) return reject("initialCapital must be > 0")
        template.validate(resolved)?.let { return reject("${template.id}: $it") }

        val strategy = template.withParams(resolved, timeframe)
        // Warmup is the strategy's own business — deriving it from a parameter name (the old
        // `smaSlowPeriod` guess) silently under-warms any strategy that has no SMA.
        val warmupCalendarDays = warmupCalendarDays(strategy.inputs.warmupBars, timeframe)
        val ensureFrom = req.from.minusDays(warmupCalendarDays)

        // Benchmarks: each symbol's sector ETF (from its universe row) + SPY as the broad market.
        // Included in the coverage fetch so their history downloads on demand alongside the symbols;
        // the engine only reads whatever ends up stored (backtest profile never fetches).
        val benchmarkSymbols =
            (symbols.mapNotNull { SectorEtf.forSector(universePort.get(it)?.sector) } + SectorEtf.BROAD_MARKET)
                .distinct()
                .filterNot { it in symbols }

        // Data-on-demand: fetch only the missing head/tail, then wait for it (bounded). The UI will
        // later make this async with progress; for the API a bounded wait is fine (2nd run is instant).
        val job = historicalData.ensureCoverage(symbols + benchmarkSymbols, ensureFrom, req.to, timeframe)
        // Adaptive poll: already-covered ranges (every sweep request after the first) finish in
        // milliseconds — a fixed 2s tick made that the dominant per-request cost.
        var waitedMs = 0L
        while (historicalData.getJob(job.id)?.status == FetchJobStatus.RUNNING && waitedMs < MAX_WAIT_SECONDS * 1000L) {
            val step = if (waitedMs < 2_000L) 100L else 2_000L
            delay(step)
            waitedMs += step
        }
        val finished = historicalData.getJob(job.id)
        if (finished?.status == FetchJobStatus.RUNNING) {
            logger.warn { "Stock backtest: data fetch still running after ${MAX_WAIT_SECONDS}s — running on partial data" }
        }

        val engine = BacktestEngine(barStore)
        val result =
            engine.run<StrategyTrade>(
                BacktestEngine.Request(
                    symbols = symbols,
                    from = req.from,
                    to = req.to,
                    initialCapital = req.initialCapital ?: BigDecimal("20000"),
                    // Engine-level cap must mirror the strategy's own — the Request default (3)
                    // would silently clip a user asking for more. A strategy that declares no cap
                    // param is capped by the request/engine default alone.
                    maxOpenPositions = resolved.intOrNull("maxOpenPositions") ?: DEFAULT_MAX_OPEN_POSITIONS,
                    warmupDays = warmupCalendarDays,
                    holdOvernight = req.holdOvernight ?: true, // swing default: no intraday EOD liquidation
                    trailStopRMultiple = req.trailStopRMultiple,
                    timeframe = timeframe,
                    benchmarkSymbols = benchmarkSymbols,
                ),
                StrategyBacktestAdapter(strategy),
            )
        // The engine logs the result summary; this line pairs the strategy params with it.
        logger.info { "Stock backtest [${template.id}] params: ${resolved.asMap()}" }
        return ResponseEntity.status(HttpStatus.OK).body<Any>(result)
    }

    private fun reject(reason: String): ResponseEntity<Any> {
        logger.warn { "Stock backtest rejected: $reason" }
        return ResponseEntity.badRequest().body<Any>(mapOf("error" to reason))
    }

    companion object {
        private const val MAX_WAIT_SECONDS = 600
        private const val DEFAULT_MAX_OPEN_POSITIONS = 3

        /**
         * Warm-up bars → calendar days to fetch before the window, via trading days (~5 per 7
         * calendar) with a 2× safety factor for holidays and thin history. Counting in bars keeps
         * intraday timeframes honest: 200 five-minute bars is three sessions, not 200 days.
         */
        fun warmupCalendarDays(
            warmupBars: Int,
            timeframe: Timeframe,
        ): Long {
            val barsPerTradingDay =
                when (timeframe) {
                    Timeframe.DAILY -> 1.0
                    Timeframe.FOUR_HOUR -> 2.0 // RTH session ≈ 6.5h → 2 four-hour bars
                    Timeframe.FIVE_MIN -> 78.0 // 6.5h RTH / 5 min
                }
            val tradingDays = ceil(warmupBars / barsPerTradingDay).toLong()
            return (tradingDays * 2L).coerceAtLeast(MIN_WARMUP_CALENDAR_DAYS)
        }

        private const val MIN_WARMUP_CALENDAR_DAYS = 30L
    }
}
