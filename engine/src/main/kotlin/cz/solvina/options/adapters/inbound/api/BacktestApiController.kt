package cz.solvina.options.adapters.inbound.api

import com.fasterxml.jackson.annotation.JsonProperty
import cz.solvina.options.domain.features.backtest.BacktestEngine
import cz.solvina.options.domain.features.backtest.FlagBacktestStrategy
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.flag.config.FlagStrategyConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.models.Symbol
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@RestController
@RequestMapping("/api/backtest")
class BacktestApiController(
    private val barStore: BarStorePort,
    private val strategyConfig: FlagStrategyConfig,
) {
    data class FlagBacktestRequest(
        val symbols: List<String>,
        val from: LocalDate,
        val to: LocalDate,
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
    )

    data class FlagBacktestResponse(
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

        val tradingConfig = FlagTradingConfig(
            riskPerTrade = request.riskPerTrade,
            maxOpenPositions = request.maxOpenPositions,
            enabled = true,
            entryBlockMinutesBeforeClose = request.entryBlockMinutesBeforeClose,
        )
        val effectiveStrategyConfig = strategyConfig.copy(
            skipFirstRthMinutes = request.skipFirstRthMinutes ?: strategyConfig.skipFirstRthMinutes,
            requireNegativeChannelSlope = request.requireNegativeChannelSlope ?: strategyConfig.requireNegativeChannelSlope,
            minFlagpoleAtrMultiple = request.minFlagpoleAtrMultiple ?: strategyConfig.minFlagpoleAtrMultiple,
            maxFlagpoleAtrMultiple = request.maxFlagpoleAtrMultiple ?: strategyConfig.maxFlagpoleAtrMultiple,
            minFlagRetracementPct = request.minFlagRetracementPct ?: strategyConfig.minFlagRetracementPct,
            minFlagBarsForEntry = request.minFlagBarsForEntry ?: strategyConfig.minFlagBarsForEntry,
        )
        val strategy = FlagBacktestStrategy(effectiveStrategyConfig, tradingConfig)
        val engine = BacktestEngine(barStore)
        val engineRequest = BacktestEngine.Request(
            symbols = request.symbols.map { Symbol(it.uppercase()) },
            from = request.from,
            to = request.to,
            initialCapital = request.initialCapital,
            maxOpenPositions = request.maxOpenPositions,
        )

        val result = engine.run<FlagPosition>(engineRequest, strategy)
        val s = result.summary

        return ResponseEntity.ok(
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
            ),
        )
    }

    private fun FlagPosition.toDto() = BacktestTradeDto(
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
