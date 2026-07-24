package cz.solvina.options.adapters.inbound.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.BacktestRunEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.BacktestRunRepository
import cz.solvina.options.domain.features.backtest.BacktestEngine
import cz.solvina.options.domain.features.backtest.FlagBacktestStrategy
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.flag.config.FlagStrategyConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@RestController
@RequestMapping("/backtest")
class BacktestApiController(
    private val barStore: BarStorePort,
    private val strategyConfig: FlagStrategyConfig,
    private val runRepository: BacktestRunRepository,
    private val objectMapper: ObjectMapper,
) {
    data class FlagBacktestRequest(
        val symbols: List<String>,
        val from: LocalDate,
        val to: LocalDate,
        // Optional human label for this run, e.g. "loosened-filters baseline".
        val label: String? = null,
        val initialCapital: BigDecimal = BigDecimal("20000"),
        val riskPerTrade: BigDecimal = BigDecimal("100"),
        val maxOpenPositions: Int = 3,
        val entryBlockMinutesBeforeClose: Int = 120,
        // Quality filter overrides — null means use the value from application.yml
        val skipFirstRthMinutes: Int? = null,
        val requireNegativeChannelSlope: Boolean? = null,
        val minFlagpoleAtrMultiple: Double? = null,
        val maxFlagpoleAtrMultiple: Double? = null,
        val minFlagRetracementPct: Double? = null,
        val minFlagBarsForEntry: Int? = null,
        // Exit / reward-risk levers (backtest-only sweep knobs)
        val profitTargetR: Double = 2.0,
        val stopAtrMultiple: Double? = null,
        // ATR-based stop/target as % of ATR (150.0 = 1.5×ATR). stopAtrPct wins over stopAtrMultiple;
        // targetAtrPct set → ATR target, else profitTargetR. Both null → flag-low stop / R target.
        val stopAtrPct: Double? = null,
        val targetAtrPct: Double? = null,
        // Risk per trade as % of current account equity (e.g. 1.0 = 1%); overrides riskPerTrade.
        val riskPerTradePct: Double? = null,
        // ATR lookback in bars (default from config = 14).
        val atrPeriod: Int? = null,
        // Optional buying-power ceiling: cap a position's notional at equity × maxLeverage. Null =
        // uncapped (pure risk sizing). Set e.g. 1.0 for a cash account, 4.0 for Reg-T intraday.
        val maxLeverage: Double? = null,
        val holdOvernight: Boolean = false,
        val trailStopRMultiple: Double? = null,
        val barMinutes: Int = 5,
    )

    data class FlagBacktestResponse(
        val runId: UUID? = null,
        val symbols: List<String>,
        val from: LocalDate,
        val to: LocalDate,
        val initialCapital: BigDecimal,
        val finalCapital: BigDecimal,
        val totalPnl: BigDecimal,
        val totalPnlPct: BigDecimal,
        val tradeCount: Int,
        val winCount: Int,
        val lossCount: Int,
        val eodCount: Int,
        val winRate: Double,
        val avgRMultiple: BigDecimal?,
        val avgWinR: BigDecimal?,
        val avgLossR: BigDecimal?,
        val profitFactor: BigDecimal?,
        val maxDrawdownPct: BigDecimal,
        val trades: List<BacktestTradeDto>,
    )

    data class BacktestTradeDto(
        val id: UUID,
        val symbol: String,
        val status: String,
        val openedAt: OffsetDateTime,
        val closedAt: OffsetDateTime?,
        val closeReason: String?,
        val entryPrice: BigDecimal,
        val actualEntryPrice: BigDecimal?,
        val stopLossPrice: BigDecimal,
        val profitTargetPrice: BigDecimal,
        val closePriceActual: BigDecimal?,
        val shares: Int,
        val riskAmount: BigDecimal,
        val realizedPnl: BigDecimal?,
        @get:JsonProperty("rMultiple") val rMultiple: BigDecimal?,
        val mfeR: BigDecimal?,
        val maeR: BigDecimal?,
        val timeInTradeSeconds: Int?,
        val marketSession: String?,
        val breakoutType: String?,
        val flagpoleHeight: BigDecimal?,
        val flagRetracement: BigDecimal?,
        val flagBarCount: Int?,
        val flagpoleBarCount: Int?,
        val atrAtEntry: BigDecimal?,
        val channelSlope: BigDecimal?,
        val vwapAtEntry: BigDecimal?,
        val stopDistancePct: BigDecimal?,
    )

    @PostMapping("/flag")
    suspend fun runFlagBacktest(
        @RequestBody request: FlagBacktestRequest,
    ): ResponseEntity<FlagBacktestResponse> {
        if (request.symbols.isEmpty()) return ResponseEntity.badRequest().build()
        if (request.from.isAfter(request.to)) return ResponseEntity.badRequest().build()
        // Money-management / ATR levers must be sane, else a run silently produces garbage sizing.
        if (request.riskPerTradePct != null && (request.riskPerTradePct <= 0.0 || request.riskPerTradePct > 100.0)) {
            return ResponseEntity.badRequest().build()
        }
        if (request.stopAtrPct != null && request.stopAtrPct <= 0.0) return ResponseEntity.badRequest().build()
        if (request.targetAtrPct != null && request.targetAtrPct <= 0.0) return ResponseEntity.badRequest().build()
        if (request.atrPeriod != null && request.atrPeriod < 1) return ResponseEntity.badRequest().build()
        if (request.maxLeverage != null && request.maxLeverage <= 0.0) return ResponseEntity.badRequest().build()

        val tradingConfig =
            FlagTradingConfig(
                riskPerTrade = request.riskPerTrade,
                maxOpenPositions = request.maxOpenPositions,
                enabled = true,
                entryBlockMinutesBeforeClose = request.entryBlockMinutesBeforeClose,
            )
        val effectiveStrategyConfig =
            strategyConfig.copy(
                skipFirstRthMinutes = request.skipFirstRthMinutes ?: strategyConfig.skipFirstRthMinutes,
                requireNegativeChannelSlope = request.requireNegativeChannelSlope ?: strategyConfig.requireNegativeChannelSlope,
                minFlagpoleAtrMultiple = request.minFlagpoleAtrMultiple ?: strategyConfig.minFlagpoleAtrMultiple,
                maxFlagpoleAtrMultiple = request.maxFlagpoleAtrMultiple ?: strategyConfig.maxFlagpoleAtrMultiple,
                minFlagRetracementPct = request.minFlagRetracementPct ?: strategyConfig.minFlagRetracementPct,
                minFlagBarsForEntry = request.minFlagBarsForEntry ?: strategyConfig.minFlagBarsForEntry,
            )
        val strategy =
            FlagBacktestStrategy(
                effectiveStrategyConfig,
                tradingConfig,
                profitTargetR = request.profitTargetR,
                stopAtrMultiple = request.stopAtrMultiple,
                stopAtrPct = request.stopAtrPct,
                targetAtrPct = request.targetAtrPct,
                riskPerTradePct = request.riskPerTradePct,
                atrPeriod = request.atrPeriod,
                maxLeverage = request.maxLeverage,
            )
        val engine = BacktestEngine(barStore)
        val engineRequest =
            BacktestEngine.Request(
                symbols = request.symbols.map { Symbol(it.uppercase()) },
                from = request.from,
                to = request.to,
                initialCapital = request.initialCapital,
                maxOpenPositions = request.maxOpenPositions,
                holdOvernight = request.holdOvernight,
                trailStopRMultiple = request.trailStopRMultiple,
                barMinutes = request.barMinutes,
            )

        val result = engine.run<FlagPosition>(engineRequest, strategy)
        val s = result.summary

        val response =
            FlagBacktestResponse(
                symbols = s.symbols,
                from = s.from,
                to = s.to,
                initialCapital = s.initialCapital,
                finalCapital = s.finalCapital,
                totalPnl = s.totalPnl,
                totalPnlPct = s.totalPnlPct,
                tradeCount = s.tradeCount,
                winCount = s.winCount,
                lossCount = s.lossCount,
                eodCount = s.eodCount,
                winRate = s.winRate,
                avgRMultiple = s.avgRMultiple,
                avgWinR = s.avgWinR,
                avgLossR = s.avgLossR,
                profitFactor = s.profitFactor,
                maxDrawdownPct = s.maxDrawdownPct,
                trades = result.trades.map { it.toDto() },
            )
        val runId = persistRun(request, response)
        return ResponseEntity.ok(response.copy(runId = runId))
    }

    // -------------------------------------------------------------------------
    // Persistence + history
    // -------------------------------------------------------------------------

    data class RunOverviewDto(
        val id: UUID,
        val createdAt: Instant,
        val label: String?,
        val strategy: String,
        val from: LocalDate,
        val to: LocalDate,
        val symbols: List<String>,
        val initialCapital: BigDecimal,
        val finalCapital: BigDecimal,
        val totalPnl: BigDecimal,
        val totalPnlPct: BigDecimal,
        val tradeCount: Int,
        val winCount: Int,
        val lossCount: Int,
        val eodCount: Int,
        val winRate: Double,
        val avgRMultiple: BigDecimal?,
        val profitFactor: BigDecimal?,
        val maxDrawdownPct: BigDecimal,
    )

    data class RunDetailDto(
        val overview: RunOverviewDto,
        val params: JsonNode,
        val trades: JsonNode,
    )

    /** Lists all backtest runs, newest first (overview only — no per-trade detail). */
    @GetMapping("/runs")
    suspend fun listRuns(): ResponseEntity<List<RunOverviewDto>> {
        val rows = withContext(Dispatchers.IO) { runRepository.findAllByOrderByCreatedAtDesc() }
        return ResponseEntity.ok(rows.map { it.toOverview() })
    }

    /** Full detail for one run: recallable params + summary + the per-trade report. */
    @GetMapping("/runs/{id}")
    suspend fun getRun(
        @PathVariable id: UUID,
    ): ResponseEntity<RunDetailDto> {
        val entity =
            withContext(Dispatchers.IO) { runRepository.findById(id).orElse(null) }
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            RunDetailDto(
                overview = entity.toOverview(),
                params = objectMapper.readTree(entity.paramsJson),
                trades = objectMapper.readTree(entity.tradesJson),
            ),
        )
    }

    /** Re-runs a stored run's exact parameters and persists the result as a new run. */
    @PostMapping("/runs/{id}/rerun")
    suspend fun rerun(
        @PathVariable id: UUID,
    ): ResponseEntity<FlagBacktestResponse> {
        val entity =
            withContext(Dispatchers.IO) { runRepository.findById(id).orElse(null) }
                ?: return ResponseEntity.notFound().build()
        val request = objectMapper.readValue(entity.paramsJson, FlagBacktestRequest::class.java)
        return runFlagBacktest(request)
    }

    private suspend fun persistRun(
        request: FlagBacktestRequest,
        response: FlagBacktestResponse,
    ): UUID {
        val entity =
            BacktestRunEntity(
                createdAt = Instant.now(),
                label = request.label,
                strategy = "flag",
                fromDate = response.from,
                toDate = response.to,
                symbols = response.symbols.joinToString(","),
                paramsJson = objectMapper.writeValueAsString(request),
                initialCapital = response.initialCapital,
                finalCapital = response.finalCapital,
                totalPnl = response.totalPnl,
                totalPnlPct = response.totalPnlPct,
                tradeCount = response.tradeCount,
                winCount = response.winCount,
                lossCount = response.lossCount,
                eodCount = response.eodCount,
                winRate = response.winRate,
                avgRMultiple = response.avgRMultiple,
                avgWinR = response.avgWinR,
                avgLossR = response.avgLossR,
                profitFactor = response.profitFactor,
                maxDrawdownPct = response.maxDrawdownPct,
                tradesJson = objectMapper.writeValueAsString(response.trades),
            )
        return withContext(Dispatchers.IO) { runRepository.save(entity) }.id!!
    }

    private fun BacktestRunEntity.toOverview() =
        RunOverviewDto(
            id = id!!,
            createdAt = createdAt,
            label = label,
            strategy = strategy,
            from = fromDate,
            to = toDate,
            symbols = symbols.split(",").filter { it.isNotBlank() },
            initialCapital = initialCapital,
            finalCapital = finalCapital,
            totalPnl = totalPnl,
            totalPnlPct = totalPnlPct,
            tradeCount = tradeCount,
            winCount = winCount,
            lossCount = lossCount,
            eodCount = eodCount,
            winRate = winRate,
            avgRMultiple = avgRMultiple,
            profitFactor = profitFactor,
            maxDrawdownPct = maxDrawdownPct,
        )

    private fun FlagPosition.toDto() =
        BacktestTradeDto(
            id = id ?: UUID.randomUUID(),
            symbol = symbol.value,
            status = status.name,
            openedAt = OffsetDateTime.ofInstant(openedAt, ZoneOffset.UTC),
            closedAt = closedAt?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            closeReason = closeReason,
            entryPrice = entryPrice,
            actualEntryPrice = actualEntryPrice,
            stopLossPrice = stopLossPrice,
            profitTargetPrice = profitTargetPrice,
            closePriceActual = closePriceActual,
            shares = shares,
            riskAmount = riskAmount,
            realizedPnl = realizedPnl,
            rMultiple = rMultiple,
            mfeR = mfeR,
            maeR = maeR,
            timeInTradeSeconds = timeInTradeSeconds,
            marketSession = marketSession,
            breakoutType = breakoutType,
            flagpoleHeight = flagpoleHeight,
            flagRetracement = flagRetracement,
            flagBarCount = flagBarCount,
            flagpoleBarCount = flagpoleBarCount,
            atrAtEntry = atrAtEntry,
            channelSlope = channelSlope,
            vwapAtEntry = vwapAtEntry,
            stopDistancePct = stopDistancePct,
        )
}
