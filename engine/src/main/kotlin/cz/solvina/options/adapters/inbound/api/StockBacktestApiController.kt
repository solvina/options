package cz.solvina.options.adapters.inbound.api

import cz.solvina.options.domain.features.backtest.BacktestEngine
import cz.solvina.options.domain.features.backtest.RuleBacktestStrategy
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.FetchJobStatus
import cz.solvina.options.domain.features.bars.HistoricalDataService
import cz.solvina.options.domain.features.bars.Timeframe
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
@RestController
@RequestMapping("/api/backtest")
class StockBacktestApiController(
    private val historicalData: HistoricalDataService,
    private val barStore: BarStorePort,
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
        val riskPerTrade: Double? = null,
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
                riskPerTrade = req.riskPerTrade ?: d.riskPerTrade,
                maxOpenPositions = req.maxOpenPositions ?: d.maxOpenPositions,
            )

        // Warmup must cover the slowest SMA before `from`; fetch that too. Trading-day → calendar-day
        // padding factor of ~1.6 (5 weekdays / ~7 calendar).
        val warmupCalendarDays = (maxOf(params.smaSlowPeriod, params.smaFastPeriod) * 2L).coerceAtLeast(400L)
        val ensureFrom = req.from.minusDays(warmupCalendarDays)

        // Data-on-demand: fetch only the missing head/tail, then wait for it (bounded). The UI will
        // later make this async with progress; for the API a bounded wait is fine (2nd run is instant).
        val job = historicalData.ensureCoverage(symbols, ensureFrom, req.to, timeframe)
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
                    warmupDays = warmupCalendarDays,
                    holdOvernight = true, // swing: hold to stop/target, no intraday EOD liquidation
                    timeframe = timeframe,
                ),
                strategy,
            )
        return ResponseEntity.status(HttpStatus.OK).body(result)
    }

    companion object {
        private const val MAX_WAIT_SECONDS = 600
    }
}
