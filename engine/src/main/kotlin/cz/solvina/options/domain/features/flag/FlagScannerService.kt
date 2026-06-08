package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.bars.AtrCalculator
import cz.solvina.options.domain.features.bars.BarAggregator
import cz.solvina.options.domain.features.bars.BarBuffer
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.EquityHistoricalBarsPort
import cz.solvina.options.domain.features.bars.RealTimeBarsPort
import cz.solvina.options.domain.features.bars.VolumeAnalysis
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.flag.FlagExecutionService.ExecutionRequest
import cz.solvina.options.domain.features.flag.config.FlagStrategyConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfigPort
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import cz.solvina.options.domain.features.flag.model.FlagStatus

private val logger = KotlinLogging.logger {}

private const val STALE_BAR_MINUTES = 10L

@Service
class FlagScannerService(
    private val realTimeBarsPort: RealTimeBarsPort,
    private val equityHistoricalBarsPort: EquityHistoricalBarsPort,
    private val flagExecutionService: FlagExecutionService,
    private val flagPort: FlagPort,
    private val flagManagementService: FlagManagementService,
    private val flagTradingConfigPort: FlagTradingConfigPort,
    private val barStorePort: BarStorePort,
    private val strategyConfig: FlagStrategyConfig,
    private val connectionStatusPort: ConnectionStatusPort,
    private val universePort: UniversePort,
    private val scope: CoroutineScope,
    private val clock: Clock,
) {
    private val subscriptions = ConcurrentHashMap<Symbol, Job>()
    private val aggregators = ConcurrentHashMap<Symbol, BarAggregator>()
    private val buffers = ConcurrentHashMap<Symbol, BarBuffer>()
    private val detectors = ConcurrentHashMap<Symbol, PatternDetector>()
    // Serialises the open-position count check and order submission so two concurrent breakout
    // signals cannot both read below maxOpenPositions before either one persists PENDING.
    private val entryMutex = Mutex()

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

    fun subscribeSymbol(symbolStr: String, session: String): Boolean {
        val symbol = Symbol(symbolStr.uppercase())
        if (subscriptions[symbol]?.isActive == true) {
            logger.info { "[${symbol.value}] Hot-subscribe: already active (session=$session)" }
            return false
        }
        logger.info { "[${symbol.value}] Hot-subscribe: adding to scanner (session=$session)" }
        subscribe(symbol)
        return true
    }

    // Runs just after EU open (09:01 Berlin) and just after US open (15:31 CEST = 09:31 ET).
    // Resubscribes any watchlist symbols whose stream ended at the previous day's close.
    @Scheduled(cron = "0 1 9 * * MON-FRI", zone = "Europe/Berlin")
    fun onEuMarketOpen() = resubscribeWatchlist(strategyConfig.euWatchlist, "EU open resubscription")

    @Scheduled(cron = "0 31 9 * * MON-FRI", zone = "America/New_York")
    fun onUsMarketOpen() = resubscribeWatchlist(strategyConfig.usWatchlist, "US open resubscription")

    // Runs every 5 minutes. Detects symbols whose last bar is older than STALE_BAR_MINUTES during
    // market hours and resubscribes them — handles silent IBKR subscription drops.
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    fun watchdogCheck() {
        if (!connectionStatusPort.isConnected()) return
        val anyMarketOpen = subscriptions.keys.any { universePort.isMarketOpen(it) }
        if (!anyMarketOpen) return

        val staleThreshold = Instant.now(clock).minus(STALE_BAR_MINUTES, ChronoUnit.MINUTES)
        val stale = subscriptions.keys.filter { symbol ->
            val lastBar = buffers[symbol]?.snapshot()?.lastOrNull()
            val jobActive = subscriptions[symbol]?.isActive == true
            // Stale: job alive but no bar received within the staleness window.
            // A dead job is handled by onEuMarketOpen/onUsMarketOpen; we only resubscribe
            // jobs that are still alive (from Kotlin's perspective) but IBKR stopped sending.
            jobActive && (lastBar == null || lastBar.time.isBefore(staleThreshold))
        }

        if (stale.isEmpty()) return
        logger.warn { "Flag scanner watchdog: ${stale.map { it.value }} appear stale (no bar in ${STALE_BAR_MINUTES}min) — resubscribing" }
        stale.forEach { symbol ->
            subscriptions[symbol]?.cancel()
            subscriptions.remove(symbol)
            subscribe(symbol)
        }
    }

    private fun resubscribeWatchlist(watchlist: List<String>, reason: String) {
        val symbols = watchlist.map { Symbol(it) }
        val stale = symbols.filter { subscriptions[it]?.isActive != true }
        if (stale.isEmpty()) {
            logger.debug { "Flag scanner $reason: all ${symbols.size} symbols already active" }
            return
        }
        logger.info { "Flag scanner $reason: resubscribing ${stale.map { it.value }}" }
        stale.forEach { subscribe(it) }
    }

    private fun resolveWatchlist(): List<Symbol> {
        val all = (strategyConfig.euWatchlist + strategyConfig.usWatchlist).map { Symbol(it) }
        val open = all.filter { universePort.isMarketOpen(it) }
        open.groupBy { universePort.getMarketSchedule(it).session }.forEach { (session, symbols) ->
            logger.info { "Flag scanner: $session market open — scanning ${symbols.map { it.value }}" }
        }
        if (open.isEmpty()) logger.warn { "Flag scanner: no markets open at startup — no subscriptions created" }
        return open
    }

    internal fun isEntryBlocked(symbol: Symbol, config: FlagTradingConfig, barTime: Instant): Boolean {
        val schedule = universePort.getMarketSchedule(symbol)
        val barLocal = barTime.atZone(schedule.zone).toLocalTime()
        return !barLocal.isBefore(schedule.close.minusMinutes(config.entryBlockMinutesBeforeClose.toLong()))
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
                        // Update high/low watermarks for any open position on this symbol
                        flagManagementService.updateWatermarksForSymbol(symbol, BigDecimal.valueOf(bar.close))

                        // 1. Check for 5-min candle completion
                        val completed = aggregator.add(bar)
                        if (completed != null) {
                            barStorePort.writeBar(symbol, completed)
                            buffer.add(completed)
                            val state = detector.onNewBar(completed)
                            if (state is PatternState.BreakoutReady) {
                                maybeEnter(symbol, state, "FIVE_MIN", completed.time)
                            }
                        }

                        // 2. Also check breakout on live 5-sec bar close (sub-candle precision).
                        // Read state snapshot first — checkBreakoutOnLiveBar is pure (no mutation).
                        val currentState = detector.state
                        if (currentState is PatternState.FlagForming) {
                            val breakout = detector.checkBreakoutOnLiveBar(bar.close, currentState.pole, currentState.flag)
                            if (breakout != null) {
                                maybeEnter(symbol, breakout, "LIVE_BAR", bar.time)
                            }
                        }
                    }

                // Stream ended (market closed). Remove stale Job so onUsMarketOpen/onEuMarketOpen
                // can resubscribe cleanly the next morning.
                subscriptions.remove(symbol)
                logger.info { "[${symbol.value}] Real-time bar stream ended — subscription removed" }
            }

        subscriptions[symbol] = job
    }

    private fun maybeEnter(
        symbol: Symbol,
        breakout: PatternState.BreakoutReady,
        breakoutType: String,
        barTime: Instant,
    ) {
        scope.launch {
            val config = flagTradingConfigPort.get()

            if (!config.enabled) {
                logger.info { "[${symbol.value}] Breakout detected but scanner is paused — skipping" }
                detectors[symbol]?.reset()
                return@launch
            }

            if (isEntryBlocked(symbol, config, barTime)) {
                logger.info { "[${symbol.value}] Entry blocked — within ${config.entryBlockMinutesBeforeClose} min of market close" }
                detectors[symbol]?.reset()
                return@launch
            }

            val entryPrice = BigDecimal(breakout.resistanceLevel).setScale(2, RoundingMode.HALF_UP)
            val stopLossPrice = BigDecimal(breakout.flag.lowestLow)
                .subtract(BigDecimal("0.01"))
                .setScale(2, RoundingMode.HALF_UP)

            val schedule = universePort.getMarketSchedule(symbol)
            val marketSession = schedule.session
            val zone = schedule.zone
            val closeTime = schedule.close
            val barZoned = barTime.atZone(zone)
            val minutesToClose = ChronoUnit.MINUTES.between(barZoned.toLocalTime(), closeTime).toInt().coerceAtLeast(0)
            val flagAvgVolume = breakout.flag.bars.map { it.volume.toLong() }.average().toLong()

            val bars = buffers[symbol]?.snapshot() ?: emptyList()
            val atrAtEntry = AtrCalculator.atr(bars, strategyConfig.atrPeriod).takeIf { !it.isNaN() }
            val volumeMaRaw = VolumeAnalysis.volumeMa(bars, strategyConfig.volumeMaPeriod).takeIf { !it.isNaN() }
            val volumeMaAtEntry = volumeMaRaw?.toLong()
            val flagpoleVolumeRatio = if (volumeMaRaw != null && volumeMaRaw > 0)
                breakout.pole.avgVolume / volumeMaRaw else null

            val sessionOpenLocal = schedule.open
            val sessionStart = barZoned.toLocalDate().atTime(sessionOpenLocal).atZone(zone).toInstant()
            val todayBars = bars.filter { !it.time.isBefore(sessionStart) }
            val dayOpenPrice = todayBars.firstOrNull()?.open
            val vwapAtEntry = if (todayBars.isNotEmpty()) {
                val totalVol = todayBars.sumOf { it.volume.toDouble() }
                if (totalVol > 0) todayBars.sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume } / totalVol
                else null
            } else null

            // Quality filters
            val minutesSinceOpen = ChronoUnit.MINUTES.between(sessionStart, barTime).toInt().coerceAtLeast(0)
            if (minutesSinceOpen < strategyConfig.skipFirstRthMinutes) {
                logger.debug { "[${symbol.value}] Entry skipped — ${minutesSinceOpen}min since open < skipFirstRthMinutes(${strategyConfig.skipFirstRthMinutes})" }
                detectors[symbol]?.reset()
                return@launch
            }
            if (strategyConfig.requireNegativeChannelSlope && breakout.flag.upperResistance.slope >= 0) {
                logger.debug { "[${symbol.value}] Entry skipped — channel slope ${breakout.flag.upperResistance.slope} is not negative" }
                detectors[symbol]?.reset()
                return@launch
            }
            if (atrAtEntry != null && atrAtEntry > 0) {
                val poleAtrRatio = breakout.pole.height / atrAtEntry
                if (poleAtrRatio < strategyConfig.minFlagpoleAtrMultiple) {
                    logger.debug { "[${symbol.value}] Entry skipped — pole/ATR ratio ${poleAtrRatio} < min(${strategyConfig.minFlagpoleAtrMultiple})" }
                    detectors[symbol]?.reset()
                    return@launch
                }
                if (poleAtrRatio > strategyConfig.maxFlagpoleAtrMultiple) {
                    logger.debug { "[${symbol.value}] Entry skipped — pole/ATR ratio ${poleAtrRatio} > max(${strategyConfig.maxFlagpoleAtrMultiple})" }
                    detectors[symbol]?.reset()
                    return@launch
                }
            }
            val retracementPct = Math.abs(breakout.flag.retracement)
            if (retracementPct < strategyConfig.minFlagRetracementPct) {
                logger.debug { "[${symbol.value}] Entry skipped — retracement ${retracementPct * 100}% < min(${strategyConfig.minFlagRetracementPct * 100}%)" }
                detectors[symbol]?.reset()
                return@launch
            }
            if (breakout.flag.bars.size < strategyConfig.minFlagBarsForEntry) {
                logger.debug { "[${symbol.value}] Entry skipped — flag bars ${breakout.flag.bars.size} < min(${strategyConfig.minFlagBarsForEntry})" }
                detectors[symbol]?.reset()
                return@launch
            }

            val stopDistancePct = entryPrice.subtract(stopLossPrice)
                .divide(entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal("100"))
                .setScale(4, RoundingMode.HALF_UP)

            val request =
                ExecutionRequest(
                    symbol = symbol,
                    entryPrice = entryPrice,
                    stopLossPrice = stopLossPrice,
                    flagpoleHeight = BigDecimal(breakout.pole.height).setScale(4, RoundingMode.HALF_UP),
                    flagRetracement = BigDecimal(breakout.flag.retracement).setScale(4, RoundingMode.HALF_UP),
                    resistanceAtEntry = entryPrice,
                    patternStartedAt = breakout.pole.startBar.time,
                    signalTime = barTime,
                    tradingConfig = config,
                    flagBarCount = breakout.flag.bars.size,
                    flagpoleBarCount = breakout.pole.barCount,
                    flagpoleAvgVolume = breakout.pole.avgVolume.toLong(),
                    flagAvgVolume = flagAvgVolume,
                    channelSlope = BigDecimal(breakout.flag.upperResistance.slope).setScale(7, RoundingMode.HALF_UP),
                    marketSession = marketSession,
                    minutesToClose = minutesToClose,
                    atrAtEntry = atrAtEntry?.let { BigDecimal(it).setScale(4, RoundingMode.HALF_UP) },
                    volumeMaAtEntry = volumeMaAtEntry,
                    flagpoleVolumeRatio = flagpoleVolumeRatio?.let { BigDecimal(it).setScale(3, RoundingMode.HALF_UP) },
                    vwapAtEntry = vwapAtEntry?.let { BigDecimal(it).setScale(4, RoundingMode.HALF_UP) },
                    dayOpenPrice = dayOpenPrice?.let { BigDecimal(it).setScale(4, RoundingMode.HALF_UP) },
                    breakoutType = breakoutType,
                    stopDistancePct = stopDistancePct,
                )

            // Serialise position count check + submission to close the TOCTOU window where two
            // concurrent breakout signals could both read below maxOpenPositions before either persists.
            entryMutex.withLock {
                val openCount = flagPort.findOpen().size +
                    flagPort.findByStatus(FlagStatus.PENDING).size
                if (openCount >= config.maxOpenPositions) {
                    logger.info { "[${symbol.value}] Max open positions (${config.maxOpenPositions}) reached — skipping" }
                    detectors[symbol]?.reset()
                    return@withLock
                }
                detectors[symbol]?.reset() // prevent double-firing
                flagExecutionService.execute(request)
            }
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
