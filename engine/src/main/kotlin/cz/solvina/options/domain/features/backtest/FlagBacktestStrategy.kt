package cz.solvina.options.domain.features.backtest

import cz.solvina.options.domain.features.bars.AtrCalculator
import cz.solvina.options.domain.features.bars.BarBuffer
import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.features.bars.VolumeAnalysis
import cz.solvina.options.domain.features.flag.Flag
import cz.solvina.options.domain.features.flag.Flagpole
import cz.solvina.options.domain.features.flag.PatternDetector
import cz.solvina.options.domain.features.flag.PatternState
import cz.solvina.options.domain.features.flag.config.FlagStrategyConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Implements [BacktestableStrategy] for the bull-flag pattern.
 *
 * Reuses [PatternDetector] unchanged — it has no knowledge of the backtest context.
 * Entry/sizing formulas are identical to the live [FlagScannerService] / [FlagExecutionService].
 */
class FlagBacktestStrategy(
    private val strategyConfig: FlagStrategyConfig,
    private val tradingConfig: FlagTradingConfig,
) : BacktestableStrategy {

    private val detectors = mutableMapOf<Symbol, PatternDetector>()
    private val buffers = mutableMapOf<Symbol, BarBuffer>()
    private val pending = mutableMapOf<String, FlagPosition>()  // tradeId → position
    private val completed = mutableListOf<FlagPosition>()
    private val counter = AtomicInteger(0)

    // -------------------------------------------------------------------------
    // BacktestableStrategy implementation
    // -------------------------------------------------------------------------

    override fun initialize(symbols: List<Symbol>, warmupBars: Map<Symbol, List<FiveMinuteBar>>) {
        for (symbol in symbols) {
            val buffer = BarBuffer()
            val detector = PatternDetector(symbol.value, buffer, strategyConfig)
            warmupBars[symbol]?.forEach { bar ->
                buffer.add(bar)
                detector.onNewBar(bar)
            }
            buffers[symbol] = buffer
            detectors[symbol] = detector
            logger.debug { "[${symbol.value}] Initialized with ${warmupBars[symbol]?.size ?: 0} warm-up bars" }
        }
    }

    override fun onBar(
        symbol: Symbol,
        bar: FiveMinuteBar,
        account: BacktestAccountView,
    ): List<BacktestSignal> {
        val buffer = buffers[symbol] ?: return emptyList()
        val detector = detectors[symbol] ?: return emptyList()

        buffer.add(bar)
        val state = detector.onNewBar(bar) as? PatternState.BreakoutReady ?: return emptyList()

        if (account.openPositions + account.pendingPositions >= tradingConfig.maxOpenPositions) {
            detector.reset()
            return emptyList()
        }

        val session = detectSession(bar.time)
        if (isEntryBlocked(bar.time, session)) {
            logger.debug { "[${symbol.value}] Entry blocked — within ${tradingConfig.entryBlockMinutesBeforeClose} min of close" }
            detector.reset()
            return emptyList()
        }

        val signal = buildSignal(symbol, bar, state, buffer, session) ?: run {
            detector.reset()
            return emptyList()
        }
        detector.reset()
        return listOf(signal)
    }

    override fun onEntryFilled(tradeId: String, fillPrice: BigDecimal, filledAt: Instant) {
        val pos = pending[tradeId] ?: return
        pending[tradeId] = pos.copy(
            status = FlagStatus.OPEN,
            actualEntryPrice = fillPrice,
            entrySlippage = fillPrice.subtract(pos.entryPrice),
        )
    }

    override fun onEntryExpired(tradeId: String) {
        pending.remove(tradeId)
    }

    override fun onPositionClosed(
        tradeId: String,
        closePrice: BigDecimal,
        closeReason: String,
        closedAt: Instant,
        highestSeen: BigDecimal,
        lowestSeen: BigDecimal,
    ) {
        val pos = pending.remove(tradeId) ?: return
        val actualEntry = pos.actualEntryPrice ?: pos.entryPrice
        val pnl = closePrice.subtract(actualEntry)
            .multiply(BigDecimal(pos.shares))
            .setScale(2, RoundingMode.HALF_UP)
        val shares = BigDecimal(pos.shares)
        val mfe = highestSeen.subtract(actualEntry).multiply(shares).setScale(2, RoundingMode.HALF_UP)
        val mae = actualEntry.subtract(lowestSeen).multiply(shares).setScale(2, RoundingMode.HALF_UP)
        val rMultiple = if (pos.riskAmount > BigDecimal.ZERO)
            pnl.divide(pos.riskAmount, 2, RoundingMode.HALF_UP) else null
        val mfeR = if (pos.riskAmount > BigDecimal.ZERO)
            mfe.divide(pos.riskAmount, 2, RoundingMode.HALF_UP) else null
        val maeR = if (pos.riskAmount > BigDecimal.ZERO)
            mae.divide(pos.riskAmount, 2, RoundingMode.HALF_UP) else null
        val status = when (closeReason) {
            "profit_target" -> FlagStatus.CLOSED_PROFIT
            "stop_loss" -> FlagStatus.CLOSED_STOP
            "eod_liquidation" -> FlagStatus.CLOSED_EOD
            else -> FlagStatus.CLOSED_MANUAL
        }
        completed.add(
            pos.copy(
                status = status,
                closedAt = closedAt,
                closeReason = closeReason,
                closePriceActual = closePrice,
                realizedPnl = pnl,
                highestPriceSeen = highestSeen,
                lowestPriceSeen = lowestSeen,
                maxFavorableExcursion = mfe,
                maxAdverseExcursion = mae,
                rMultiple = rMultiple,
                mfeR = mfeR,
                maeR = maeR,
                timeInTradeSeconds = ChronoUnit.SECONDS.between(pos.openedAt, closedAt).toInt(),
            ),
        )
    }

    override fun trades(): List<FlagPosition> = completed.toList()

    // -------------------------------------------------------------------------
    // Signal construction (mirrors FlagScannerService.maybeEnter + FlagExecutionService)
    // -------------------------------------------------------------------------

    private fun buildSignal(
        symbol: Symbol,
        bar: FiveMinuteBar,
        state: PatternState.BreakoutReady,
        buffer: BarBuffer,
        session: String,
    ): BacktestSignal.OpenBracket? {
        val entryPrice = BigDecimal(state.resistanceLevel).setScale(2, RoundingMode.HALF_UP)
        val stopLossPrice = BigDecimal(state.flag.lowestLow)
            .subtract(BigDecimal("0.01"))
            .setScale(2, RoundingMode.HALF_UP)
        val risk = entryPrice.subtract(stopLossPrice).abs()
        if (risk <= BigDecimal.ZERO) return null

        val shares = tradingConfig.riskPerTrade
            .divide(risk, 0, RoundingMode.FLOOR)
            .toInt()
            .coerceAtLeast(1)
        val profitTarget = entryPrice
            .add(entryPrice.subtract(stopLossPrice).multiply(BigDecimal.valueOf(2)))
            .setScale(2, RoundingMode.HALF_UP)

        val bars = buffer.snapshot()
        val atrAtEntry = AtrCalculator.atr(bars, strategyConfig.atrPeriod).takeIf { !it.isNaN() }
        val volumeMaRaw = VolumeAnalysis.volumeMa(bars, strategyConfig.volumeMaPeriod).takeIf { !it.isNaN() }
        val flagAvgVolume = state.flag.bars.map { it.volume.toLong() }.average().toLong()
        val flagpoleVolumeRatio = if (volumeMaRaw != null && volumeMaRaw > 0)
            state.pole.avgVolume / volumeMaRaw else null
        val stopDistancePct = entryPrice.subtract(stopLossPrice)
            .divide(entryPrice, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
            .setScale(4, RoundingMode.HALF_UP)

        val zone = if (session == "EU") ZoneId.of("Europe/Berlin") else ZoneId.of("America/New_York")
        val closeTime = if (session == "EU") LocalTime.of(17, 30) else LocalTime.of(16, 0)
        val sessionOpenTime = if (session == "EU") LocalTime.of(9, 0) else LocalTime.of(9, 30)
        val barZoned = bar.time.atZone(zone)
        val minutesToClose = ChronoUnit.MINUTES.between(barZoned.toLocalTime(), closeTime).toInt().coerceAtLeast(0)
        val sessionStart = barZoned.toLocalDate().atTime(sessionOpenTime).atZone(zone).toInstant()
        val todayBars = bars.filter { !it.time.isBefore(sessionStart) }
        val dayOpenPrice = todayBars.firstOrNull()?.open?.let { BigDecimal.valueOf(it).setScale(4, RoundingMode.HALF_UP) }
        val vwapAtEntry = if (todayBars.isNotEmpty()) {
            val totalVol = todayBars.sumOf { it.volume.toDouble() }
            if (totalVol > 0)
                BigDecimal.valueOf(todayBars.sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume } / totalVol)
                    .setScale(4, RoundingMode.HALF_UP)
            else null
        } else null

        // Quality filters (mirror FlagScannerService)
        val minutesSinceOpen = ChronoUnit.MINUTES.between(sessionStart, bar.time).toInt().coerceAtLeast(0)
        if (minutesSinceOpen < strategyConfig.skipFirstRthMinutes) return null
        if (strategyConfig.requireNegativeChannelSlope && state.flag.upperResistance.slope >= 0) return null
        if (atrAtEntry != null && atrAtEntry > 0) {
            val poleAtrRatio = state.pole.height / atrAtEntry
            if (poleAtrRatio < strategyConfig.minFlagpoleAtrMultiple) return null
            if (poleAtrRatio > strategyConfig.maxFlagpoleAtrMultiple) return null
        }
        if (Math.abs(state.flag.retracement) < strategyConfig.minFlagRetracementPct) return null
        if (state.flag.bars.size < strategyConfig.minFlagBarsForEntry) return null

        val tradeId = "flag-${counter.incrementAndGet()}"
        val position = FlagPosition(
            id = UUID.randomUUID(),
            symbol = symbol,
            status = FlagStatus.PENDING,
            entryOrderId = counter.get(),
            stopLossOrderId = counter.get() * 3 + 1,
            profitTargetOrderId = counter.get() * 3 + 2,
            entryPrice = entryPrice,
            stopLossPrice = stopLossPrice,
            profitTargetPrice = profitTarget,
            shares = shares,
            riskAmount = tradingConfig.riskPerTrade,
            flagpoleHeight = BigDecimal(state.pole.height).setScale(4, RoundingMode.HALF_UP),
            flagRetracement = BigDecimal(state.flag.retracement).setScale(4, RoundingMode.HALF_UP),
            resistanceAtEntry = entryPrice,
            patternStartedAt = state.pole.startBar.time,
            openedAt = bar.time,
            flagBarCount = state.flag.bars.size,
            flagpoleBarCount = state.pole.barCount,
            flagpoleAvgVolume = state.pole.avgVolume.toLong(),
            flagAvgVolume = flagAvgVolume,
            channelSlope = BigDecimal(state.flag.upperResistance.slope).setScale(7, RoundingMode.HALF_UP),
            marketSession = session,
            minutesToClose = minutesToClose,
            atrAtEntry = atrAtEntry?.let { BigDecimal(it).setScale(4, RoundingMode.HALF_UP) },
            volumeMaAtEntry = volumeMaRaw?.toLong(),
            flagpoleVolumeRatio = flagpoleVolumeRatio?.let { BigDecimal(it).setScale(3, RoundingMode.HALF_UP) },
            vwapAtEntry = vwapAtEntry,
            dayOpenPrice = dayOpenPrice,
            breakoutType = "FIVE_MIN",
            stopDistancePct = stopDistancePct,
            strategyName = "bull_flag_backtest",
        )
        pending[tradeId] = position

        return BacktestSignal.OpenBracket(
            tradeId = tradeId,
            symbol = symbol,
            shares = shares,
            entryPrice = entryPrice,
            stopLossPrice = stopLossPrice,
            profitTargetPrice = profitTarget,
        )
    }

    // -------------------------------------------------------------------------
    // Session helpers
    // -------------------------------------------------------------------------

    private fun detectSession(barTime: Instant): String {
        val nyTime = barTime.atZone(ZoneId.of("America/New_York"))
        val isWeekday = nyTime.dayOfWeek != DayOfWeek.SATURDAY && nyTime.dayOfWeek != DayOfWeek.SUNDAY
        if (isWeekday &&
            !nyTime.toLocalTime().isBefore(LocalTime.of(9, 30)) &&
            nyTime.toLocalTime().isBefore(LocalTime.of(16, 0))
        ) return "US"
        val berlinTime = barTime.atZone(ZoneId.of("Europe/Berlin"))
        val isBerlinWeekday = berlinTime.dayOfWeek != DayOfWeek.SATURDAY && berlinTime.dayOfWeek != DayOfWeek.SUNDAY
        if (isBerlinWeekday &&
            !berlinTime.toLocalTime().isBefore(LocalTime.of(9, 0)) &&
            berlinTime.toLocalTime().isBefore(LocalTime.of(17, 30))
        ) return "EU"
        return "US"
    }

    private fun isEntryBlocked(barTime: Instant, session: String): Boolean {
        val zone = if (session == "EU") ZoneId.of("Europe/Berlin") else ZoneId.of("America/New_York")
        val closeTime = if (session == "EU") LocalTime.of(17, 30) else LocalTime.of(16, 0)
        val barLocal = barTime.atZone(zone).toLocalTime()
        return !barLocal.isBefore(closeTime.minusMinutes(tradingConfig.entryBlockMinutesBeforeClose.toLong()))
    }
}
