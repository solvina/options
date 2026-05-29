package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.bars.BarAggregator
import cz.solvina.options.domain.features.bars.BarBuffer
import cz.solvina.options.domain.features.bars.EquityHistoricalBarsPort
import cz.solvina.options.domain.features.bars.RealTimeBarsPort
import cz.solvina.options.domain.features.flag.FlagExecutionService.ExecutionRequest
import cz.solvina.options.domain.features.flag.config.FlagStrategyConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfigPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Service
class FlagScannerService(
    private val realTimeBarsPort: RealTimeBarsPort,
    private val equityHistoricalBarsPort: EquityHistoricalBarsPort,
    private val flagExecutionService: FlagExecutionService,
    private val flagPort: FlagPort,
    private val flagTradingConfigPort: FlagTradingConfigPort,
    private val strategyConfig: FlagStrategyConfig,
    private val scope: CoroutineScope,
) {
    private val subscriptions = ConcurrentHashMap<Symbol, Job>()
    private val aggregators = ConcurrentHashMap<Symbol, BarAggregator>()
    private val buffers = ConcurrentHashMap<Symbol, BarBuffer>()
    private val detectors = ConcurrentHashMap<Symbol, PatternDetector>()

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        val watchlist = resolveWatchlist()
        logger.info { "Flag scanner starting — subscribing to ${watchlist.size} symbols: ${watchlist.map { it.value }}" }
        watchlist.forEach { subscribe(it) }
    }

    @PreDestroy
    fun onShutdown() {
        subscriptions.values.forEach { it.cancel() }
        subscriptions.clear()
        logger.info { "Flag scanner stopped — all bar subscriptions cancelled" }
    }

    private fun resolveWatchlist(): List<Symbol> {
        val now = ZonedDateTime.now()
        val eu = if (isEuMarketOpen(now)) strategyConfig.euWatchlist.map { Symbol(it) } else emptyList()
        val us = if (isUsMarketOpen(now)) strategyConfig.usWatchlist.map { Symbol(it) } else emptyList()
        val symbols = eu + us
        if (eu.isNotEmpty()) logger.info { "Flag scanner: EU market open — scanning ${eu.map { it.value }}" }
        if (us.isNotEmpty()) logger.info { "Flag scanner: US market open — scanning ${us.map { it.value }}" }
        if (symbols.isEmpty()) logger.warn { "Flag scanner: no markets open at startup — no subscriptions created" }
        return symbols
    }

    private fun isEuMarketOpen(now: ZonedDateTime): Boolean {
        val t = now.withZoneSameInstant(ZoneId.of("Europe/Berlin"))
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return false
        val time = t.toLocalTime()
        return !time.isBefore(LocalTime.of(9, 0)) && time.isBefore(LocalTime.of(17, 30))
    }

    private fun isUsMarketOpen(now: ZonedDateTime): Boolean {
        val t = now.withZoneSameInstant(ZoneId.of("America/New_York"))
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return false
        val time = t.toLocalTime()
        return !time.isBefore(LocalTime.of(9, 30)) && time.isBefore(LocalTime.of(16, 0))
    }

    private fun isEntryBlocked(symbol: Symbol, config: FlagTradingConfig): Boolean {
        val isEu = strategyConfig.euWatchlist.contains(symbol.value)
        val zone = if (isEu) ZoneId.of("Europe/Berlin") else ZoneId.of("America/New_York")
        val closeTime = if (isEu) LocalTime.of(17, 30) else LocalTime.of(16, 0)
        val now = ZonedDateTime.now(zone).toLocalTime()
        return !now.isBefore(closeTime.minusMinutes(config.entryBlockMinutesBeforeClose.toLong()))
    }

    private fun subscribe(symbol: Symbol) {
        val aggregator = BarAggregator(symbol.value)
        val buffer = BarBuffer()
        val detector = PatternDetector(symbol.value, buffer, strategyConfig)

        aggregators[symbol] = aggregator
        buffers[symbol] = buffer
        detectors[symbol] = detector

        val job =
            scope.launch {
                // Bootstrap with historical 5-min bars and replay through detector
                runCatching {
                    val historical = equityHistoricalBarsPort.fetch5MinBars(symbol, strategyConfig.historicalBootstrapDays)
                    buffer.addAll(historical)
                    historical.forEach { bar -> detector.onNewBar(bar) }
                    logger.info { "[${symbol.value}] Bootstrapped ${historical.size} historical bars — pattern state: ${detector.state.label()}" }
                }.onFailure { e ->
                    logger.warn { "[${symbol.value}] Historical bootstrap failed: ${e.message}" }
                }

                // Subscribe to live 5-second bars
                realTimeBarsPort
                    .streamBars(symbol)
                    .catch { e -> logger.error(e) { "[${symbol.value}] Real-time bar stream error: ${e.message}" } }
                    .collect { bar ->
                        // 1. Check for 5-min candle completion
                        val completed = aggregator.add(bar)
                        if (completed != null) {
                            buffer.add(completed)
                            val state = detector.onNewBar(completed)
                            if (state is PatternState.BreakoutReady) {
                                maybeEnter(symbol, state)
                            }
                        }

                        // 2. Also check breakout on live 5-sec bar close (sub-candle precision).
                        // Read state snapshot first — checkBreakoutOnLiveBar is pure (no mutation).
                        val currentState = detector.state
                        if (currentState is PatternState.FlagForming) {
                            val breakout = detector.checkBreakoutOnLiveBar(bar.close, currentState.pole, currentState.flag)
                            if (breakout != null) {
                                maybeEnter(symbol, breakout)
                            }
                        }
                    }
            }

        subscriptions[symbol] = job
    }

    private fun maybeEnter(
        symbol: Symbol,
        breakout: PatternState.BreakoutReady,
    ) {
        scope.launch {
            val config = flagTradingConfigPort.get()

            if (!config.enabled) {
                logger.info { "[${symbol.value}] Breakout detected but scanner is paused — skipping" }
                detectors[symbol]?.reset()
                return@launch
            }

            if (isEntryBlocked(symbol, config)) {
                logger.info { "[${symbol.value}] Entry blocked — within ${config.entryBlockMinutesBeforeClose} min of market close" }
                detectors[symbol]?.reset()
                return@launch
            }

            val openCount = flagPort.findOpen().size +
                flagPort.findByStatus(cz.solvina.options.domain.features.flag.model.FlagStatus.PENDING).size
            if (openCount >= config.maxOpenPositions) {
                logger.info { "[${symbol.value}] Max open positions (${config.maxOpenPositions}) reached — skipping" }
                detectors[symbol]?.reset()
                return@launch
            }

            val entryPrice = BigDecimal(breakout.resistanceLevel).setScale(2, java.math.RoundingMode.HALF_UP)
            val stopLossPrice = BigDecimal(breakout.flag.lowestLow)
                .subtract(BigDecimal("0.01"))
                .setScale(2, java.math.RoundingMode.HALF_UP)

            val request =
                ExecutionRequest(
                    symbol = symbol,
                    entryPrice = entryPrice,
                    stopLossPrice = stopLossPrice,
                    flagpoleHeight = BigDecimal(breakout.pole.height).setScale(4, java.math.RoundingMode.HALF_UP),
                    flagRetracement = BigDecimal(breakout.flag.retracement).setScale(4, java.math.RoundingMode.HALF_UP),
                    resistanceAtEntry = entryPrice,
                    patternStartedAt = breakout.pole.startBar.time,
                    tradingConfig = config,
                )

            detectors[symbol]?.reset() // prevent double-firing
            flagExecutionService.execute(request)
        }
    }

    // -------------------------------------------------------------------------
    // Monitoring
    // -------------------------------------------------------------------------

    data class SymbolScannerStatus(
        val symbol: String,
        val subscriptionActive: Boolean,
        val candlesBuffered: Int,
        val lastCandleAt: java.time.Instant?,
        val patternState: String,
        val poleHeightPct: Double?,
        val flagBars: Int?,
        val flagRetracementPct: Double?,
    )

    fun getScannerStatus(): List<SymbolScannerStatus> =
        subscriptions.keys.map { symbol ->
            val buffer = buffers[symbol]
            val lastCandle = buffer?.snapshot()?.lastOrNull()
            val state = detectors[symbol]?.state ?: PatternState.Idle
            SymbolScannerStatus(
                symbol = symbol.value,
                subscriptionActive = subscriptions[symbol]?.isActive == true,
                candlesBuffered = buffer?.size ?: 0,
                lastCandleAt = lastCandle?.time,
                patternState = state.label(),
                poleHeightPct = state.pole()?.let { round1(it.height / it.startBar.close * 100) },
                flagBars = state.flag()?.bars?.size,
                flagRetracementPct = state.flag()?.let { round1(it.retracement * 100) },
            )
        }.sortedBy { it.symbol }

    private fun PatternState.label(): String = when (this) {
        is PatternState.Idle -> "Idle"
        is PatternState.FlagpoleDetected -> "Pole (${consolidationBars} consol. bars)"
        is PatternState.FlagForming -> "Flag forming (${flag.bars.size} bars)"
        is PatternState.BreakoutReady -> "BREAKOUT READY"
    }

    private fun PatternState.pole(): Flagpole? = when (this) {
        is PatternState.FlagpoleDetected -> pole
        is PatternState.FlagForming -> pole
        is PatternState.BreakoutReady -> pole
        else -> null
    }

    private fun PatternState.flag(): Flag? = when (this) {
        is PatternState.FlagForming -> flag
        is PatternState.BreakoutReady -> flag
        else -> null
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}
