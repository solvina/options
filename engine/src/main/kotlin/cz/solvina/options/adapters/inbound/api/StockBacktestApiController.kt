package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.backtest.BacktestEngine
import cz.solvina.options.domain.features.backtest.RuleBacktestStrategy
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.FetchJobStatus
import cz.solvina.options.domain.features.bars.HistoricalDataService
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.universe.SectorEtf
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * Stock rule-strategy backtest. Silently ensures the required bars are downloaded/cached at the
 * chosen timeframe (data-on-demand), then runs [RuleBacktestStrategy] through the shared
 * [BacktestEngine]. First run over a cold span downloads; later runs serve from the store.
 */
// NOTE on the path: the app runs under WebFlux base-path /options, and BOTH proxies (nginx and the
// Vite dev server) rewrite the browser's /api/X to /options/X. Controllers must therefore map the
// path WITHOUT the /api prefix — "/api/backtest" here produced /options/api/backtest, which no
// proxy ever reached (every backtest endpoint 404'd from the UI).
@RestController
@RequestMapping("/backtest")
class StockBacktestApiController(
    private val historicalData: HistoricalDataService,
    private val barStore: BarStorePort,
    private val universePort: UniversePort,
) {
    data class StockBacktestRequest(
        val symbols: List<String>,
        val from: LocalDate,
        val to: LocalDate,
        val timeframe: String? = null, // "1d" (default) | "4h" | "5min"
        val initialCapital: BigDecimal? = null,
        // Rule params (null → RuleBacktestStrategy.Params defaults)
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
    )

    @PostMapping("/stock")
    suspend fun runStockBacktest(
        @RequestBody req: StockBacktestRequest,
    ): ResponseEntity<BacktestEngine.Result<RuleBacktestStrategy.RuleTrade>> {
        if (req.symbols.isEmpty() || req.from.isAfter(req.to)) return ResponseEntity.badRequest().build()
        val timeframe = Timeframe.fromLabel(req.timeframe ?: Timeframe.DAILY.label)
        val symbols = req.symbols.map { Symbol(it.trim().uppercase()) }

        val d = RuleBacktestStrategy.Params()
        val params =
            RuleBacktestStrategy.Params(
                rsiPeriod = req.rsiPeriod ?: d.rsiPeriod,
                rsiOversold = req.rsiOversold ?: d.rsiOversold,
                requireRsiRising = req.requireRsiRising ?: d.requireRsiRising,
                smaFastPeriod = req.smaFastPeriod ?: d.smaFastPeriod,
                smaSlowPeriod = req.smaSlowPeriod ?: d.smaSlowPeriod,
                requireUptrend = req.requireUptrend ?: d.requireUptrend,
                supportProximityPct = req.supportProximityPct ?: d.supportProximityPct,
                stopLossPct = req.stopLossPct ?: d.stopLossPct,
                targetPct = req.targetPct ?: d.targetPct,
                atrPeriod = req.atrPeriod ?: d.atrPeriod,
                stopAtrMultiple = req.stopAtrMultiple ?: d.stopAtrMultiple,
                targetAtrMultiple = req.targetAtrMultiple ?: d.targetAtrMultiple,
                riskPerTrade = req.riskPerTrade ?: d.riskPerTrade,
                riskPerTradePct = req.riskPerTradePct ?: d.riskPerTradePct,
                maxOpenPositions = req.maxOpenPositions ?: d.maxOpenPositions,
            )

        // Server-side param validation: a zero/negative period silently yields 0 trades (NaN-free
        // but meaningless), so reject here for EVERY client — browser input constraints only
        // protect the React form.
        validationError(params, req.initialCapital)?.let { reason ->
            logger.warn { "Stock backtest rejected: $reason | $params" }
            return ResponseEntity.badRequest().build()
        }

        // Warmup must cover the slowest SMA before `from`; fetch that too. Trading-day → calendar-day
        // padding factor of ~1.6 (5 weekdays / ~7 calendar).
        val warmupCalendarDays = (maxOf(params.smaSlowPeriod, params.smaFastPeriod) * 2L).coerceAtLeast(400L)
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
        var waited = 0
        while (historicalData.getJob(job.id)?.status == FetchJobStatus.RUNNING && waited < MAX_WAIT_SECONDS) {
            delay(2000)
            waited += 2
        }
        val finished = historicalData.getJob(job.id)
        if (finished?.status == FetchJobStatus.RUNNING) {
            logger.warn { "Stock backtest: data fetch still running after ${MAX_WAIT_SECONDS}s — running on partial data" }
        }

        val engine = BacktestEngine(barStore)
        val strategy = RuleBacktestStrategy(params)
        val result =
            engine.run<RuleBacktestStrategy.RuleTrade>(
                BacktestEngine.Request(
                    symbols = symbols,
                    from = req.from,
                    to = req.to,
                    initialCapital = req.initialCapital ?: BigDecimal("20000"),
                    // Engine-level cap must mirror the strategy's own — the Request default (3)
                    // would silently clip a user asking for more.
                    maxOpenPositions = params.maxOpenPositions,
                    warmupDays = warmupCalendarDays,
                    holdOvernight = true, // swing: hold to stop/target, no intraday EOD liquidation
                    timeframe = timeframe,
                    benchmarkSymbols = benchmarkSymbols,
                ),
                strategy,
            )
        // The engine logs the result summary; this line pairs the strategy params with it. Data-class
        // toString keeps both in sync with new fields for free.
        logger.info { "Stock backtest params: $params" }
        return ResponseEntity.status(HttpStatus.OK).body(result)
    }

    /** Returns a rejection reason, or null when the resolved params are runnable. */
    private fun validationError(
        p: RuleBacktestStrategy.Params,
        initialCapital: BigDecimal?,
    ): String? =
        when {
            p.rsiPeriod < 1 -> "rsiPeriod must be >= 1"
            p.smaFastPeriod < 1 -> "smaFastPeriod must be >= 1"
            p.smaSlowPeriod < 1 -> "smaSlowPeriod must be >= 1"
            p.maxOpenPositions < 1 -> "maxOpenPositions must be >= 1"
            p.rsiOversold <= 0.0 || p.rsiOversold > 100.0 -> "rsiOversold must be in (0, 100]"
            p.supportProximityPct < 0.0 -> "supportProximityPct must be >= 0"
            p.stopLossPct <= 0.0 -> "stopLossPct must be > 0"
            p.targetPct <= 0.0 -> "targetPct must be > 0"
            p.atrPeriod < 1 -> "atrPeriod must be >= 1"
            p.stopAtrMultiple < 0.0 -> "stopAtrMultiple must be >= 0 (0 = use stopLossPct)"
            p.targetAtrMultiple < 0.0 -> "targetAtrMultiple must be >= 0 (0 = use targetPct)"
            p.riskPerTradePct < 0.0 || p.riskPerTradePct > 100.0 -> "riskPerTradePct must be in [0, 100]"
            p.riskPerTradePct == 0.0 && p.riskPerTrade <= 0.0 -> "riskPerTrade must be > 0 when riskPerTradePct is unset"
            initialCapital != null && initialCapital <= BigDecimal.ZERO -> "initialCapital must be > 0"
            else -> null
        }

    companion object {
        private const val MAX_WAIT_SECONDS = 600
    }
}
