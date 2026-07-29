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
import cz.solvina.options.domain.features.flag.config.FLAG_STRATEGY_ID
import cz.solvina.options.domain.features.flag.config.FlagStrategyConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfigPort
import cz.solvina.options.domain.features.flag.config.from
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.market.MarketDataTypeTracker
import cz.solvina.options.domain.features.strategy.tuning.StrategyParamsResolver
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
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private const val STALE_BAR_MINUTES = 10L

// Minimum gap between (re)subscriptions of the same symbol. Prevents the watchdog from re-churning a
// symbol every tick when its data farm is down — each churn can leak a server-side reqRealTimeBars
// slot (IBKR error 456 "max real-time requests"), so we cap resubscribe frequency per symbol.
private const val RESUBSCRIBE_COOLDOWN_MINUTES = 15L

@Service
class FlagScannerService(
    private val realTimeBarsPort: RealTimeBarsPort,
    private val equityHistoricalBarsPort: EquityHistoricalBarsPort,
    private val flagExecutionService: FlagExecutionService,
    private val flagPort: FlagPort,
    private val flagManagementService: FlagManagementService,
    private val flagTradingConfigPort: FlagTradingConfigPort,
    private val barStorePort: BarStorePort,
    private val paramsResolver: StrategyParamsResolver,
    private val connectionStatusPort: ConnectionStatusPort,
    private val universePort: UniversePort,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val symbolMutexManager: SymbolMutexManager,
    private val marketDataTypeTracker: MarketDataTypeTracker,
) {
    private val subscriptions = ConcurrentHashMap<Symbol, Job>()
    private val aggregators = ConcurrentHashMap<Symbol, BarAggregator>()
    private val buffers = ConcurrentHashMap<Symbol, BarBuffer>()
    private val detectors = ConcurrentHashMap<Symbol, PatternDetector>()

    // When each symbol was last (re)subscribed — bounds watchdog resubscribe frequency (anti-leak).
    private val lastSubscribeAt = ConcurrentHashMap<Symbol, Instant>()

    // Effective (descriptor -> global -> per-symbol) parameters each subscription is running with.
    // Cached per symbol so the entry filters in maybeEnter cannot hit the DB on a breakout, and
    // refreshed only by (re)subscribe and applyParams.
    private val effectiveConfigs = ConcurrentHashMap<Symbol, FlagStrategyConfig>()

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

    fun subscribeSymbol(
        symbolStr: String,
        session: String,
    ): Boolean {
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
    @Scheduled(cron = "0 1 9 * * MON-FRI", zone = "Europe/Berlin", scheduler = "backgroundTaskScheduler")
    fun onEuMarketOpen() = resubscribeWatchlist(flagSymbolsForSession("EU"), "EU open resubscription")

    @Scheduled(cron = "0 31 9 * * MON-FRI", zone = "America/New_York", scheduler = "backgroundTaskScheduler")
    fun onUsMarketOpen() = resubscribeWatchlist(flagSymbolsForSession("US"), "US open resubscription")

    // Just after EU close (17:31 Berlin, EU close is 17:30). Real-time bar lines are held until
    // cancelled, so releasing EU subscriptions here frees market-data lines for the US session that
    // is still open. onEuMarketOpen re-subscribes the next morning. Open EU positions stay protected
    // by their broker-side GTC trailing stop; unsubscribing only stops local watermark updates.
    @Scheduled(cron = "\${flag.eu-unsubscribe-cron:0 31 17 * * MON-FRI}", zone = "Europe/Berlin", scheduler = "backgroundTaskScheduler")
    fun onEuMarketClose() = unsubscribeSession("EU")

    /** Cancel and drop every active flag subscription for [session]'s symbols (frees their IBKR lines). */
    fun unsubscribeSession(session: String) {
        val symbols = flagSymbolsForSession(session).map { Symbol(it) }
        val active = symbols.filter { subscriptions[it]?.isActive == true }
        if (active.isEmpty()) {
            logger.debug { "Flag scanner: no active $session subscriptions to unsubscribe" }
            return
        }
        logger.info { "Flag scanner: unsubscribing ${active.size} $session symbol(s) after close: ${active.map { it.value }}" }
        active.forEach { symbol ->
            subscriptions[symbol]?.cancel()
            subscriptions.remove(symbol)
        }
    }

    // Flag watchlist is DB-driven (instrument_universe.flag_enabled); split by each symbol's
    // exchange session so the US/EU open crons resubscribe only the symbols they cover.
    private fun flagSymbolsForSession(session: String): List<String> =
        universePort
            .getFlagWatchlist()
            .filter { universePort.getMarketSchedule(it).session == session }
            .map { it.value }

    // Runs every 5 minutes. Detects symbols whose last bar is older than STALE_BAR_MINUTES during
    // market hours and resubscribes them — handles silent IBKR subscription drops.
    @Scheduled(fixedDelay = 5 * 60 * 1000, scheduler = "backgroundTaskScheduler")
    fun watchdogCheck() {
        if (!connectionStatusPort.isConnected()) return
        val anyMarketOpen = subscriptions.keys.any { universePort.isMarketOpen(it) }
        if (!anyMarketOpen) return

        val now = Instant.now(clock)
        val staleThreshold = now.minus(STALE_BAR_MINUTES, ChronoUnit.MINUTES)
        val cooldownThreshold = now.minus(RESUBSCRIBE_COOLDOWN_MINUTES, ChronoUnit.MINUTES)
        val stale =
            subscriptions.keys.filter { symbol ->
                val lastBar = buffers[symbol]?.snapshot()?.lastOrNull()
                val jobActive = subscriptions[symbol]?.isActive == true
                // Skip symbols (re)subscribed within the cooldown: gives a fresh subscription time to
                // produce a bar AND prevents re-churning a dead-farm symbol every tick (the leak).
                val subscribedRecently = lastSubscribeAt[symbol]?.isAfter(cooldownThreshold) == true
                // Stale: job alive, not just (re)subscribed, and no bar within the staleness window.
                // A dead job is handled by onEuMarketOpen/onUsMarketOpen; we only resubscribe
                // jobs that are still alive (from Kotlin's perspective) but IBKR stopped sending.
                jobActive && !subscribedRecently && (lastBar == null || lastBar.time.isBefore(staleThreshold))
            }

        if (stale.isEmpty()) return
        logger.warn { "Flag scanner watchdog: ${stale.map { it.value }} appear stale (no bar in ${STALE_BAR_MINUTES}min) — resubscribing" }
        stale.forEach { symbol ->
            subscriptions[symbol]?.cancel()
            subscriptions.remove(symbol)
            subscribe(symbol)
        }
    }

    private fun resubscribeWatchlist(
        watchlist: List<String>,
        reason: String,
    ) {
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
        val all = universePort.getFlagWatchlist()
        val open = all.filter { universePort.isMarketOpen(it) }
        open.groupBy { universePort.getMarketSchedule(it).session }.forEach { (session, symbols) ->
            logger.info { "Flag scanner: $session market open — scanning ${symbols.map { it.value }}" }
        }
        if (open.isEmpty()) logger.warn { "Flag scanner: no markets open at startup — no subscriptions created" }
        return open
    }

    internal fun isEntryBlocked(
        symbol: Symbol,
        config: FlagTradingConfig,
        barTime: Instant,
    ): Boolean {
        val schedule = universePort.getMarketSchedule(symbol)
        val barLocal = barTime.atZone(schedule.zone).toLocalTime()
        return !barLocal.isBefore(schedule.close.minusMinutes(config.entryBlockMinutesBeforeClose.toLong()))
    }

    /**
     * Re-resolves parameters and rebuilds the pattern detector for [symbols] (all subscribed symbols
     * when null). This is what the UI's Apply button drives.
     *
     * The IBKR subscription is deliberately left alone — resubscribing would churn a market-data line
     * and cost 5–10 minutes of blind time while [BarAggregator] waits for the next 5-minute boundary
     * and then fills 60 bars. Only the detector is replaced, and the candle history is replayed into
     * it so pattern state is re-derived under the new parameters rather than restarting from Idle.
     *
     * Returns the number of symbols rebuilt.
     */
    suspend fun applyParams(symbols: Collection<Symbol>? = null): Int {
        val targets = (symbols ?: subscriptions.keys.toList()).filter { buffers.containsKey(it) }
        targets.forEach { rebuildDetector(it) }
        logger.info { "Flag scanner: applied parameters to ${targets.size} symbol(s): ${targets.map { it.value }}" }
        return targets.size
    }

    /**
     * Swaps in a detector built from freshly resolved parameters, replaying the existing candles.
     *
     * The buffer is rebuilt alongside it and fed incrementally, for the same reason the historical
     * bootstrap does: replaying into an already-full buffer would make every onNewBar() see the same
     * complete window and re-detect the same pole on every bar.
     */
    private suspend fun rebuildDetector(symbol: Symbol) {
        val history = buffers[symbol]?.snapshot().orEmpty()
        val config = resolveConfig(symbol)
        val buffer = BarBuffer()
        val detector = PatternDetector(symbol.value, buffer, config)
        history.forEach { bar ->
            buffer.add(bar)
            detector.onNewBar(bar)
        }
        buffers[symbol] = buffer
        detectors[symbol] = detector
        logger.info {
            "[${symbol.value}] Parameters applied over ${history.size} candles — pattern state: ${detector.state.label()}"
        }
    }

    /**
     * This symbol's effective parameters, cached for the entry filters.
     *
     * Falls back to the descriptor defaults if the tuning tables cannot be read. A transient DB blip
     * must not stop the scanner, and the descriptor defaults are the vetted baseline rather than an
     * arbitrary one — but it is logged at WARN, because trading on different parameters than the UI
     * displays is exactly the confusion this layer exists to prevent.
     */
    private suspend fun resolveConfig(symbol: Symbol): FlagStrategyConfig {
        val config =
            runCatching { FlagStrategyConfig.from(paramsResolver.effectiveParams(FLAG_STRATEGY_ID, symbol.value)) }
                .getOrElse { e ->
                    logger.warn(e) { "[${symbol.value}] Could not resolve flag params (${e.message}) — using descriptor defaults" }
                    FlagStrategyConfig.defaults()
                }
        effectiveConfigs[symbol] = config
        return config
    }

    /** Parameters a subscribed symbol is currently running with; descriptor defaults if unsubscribed. */
    internal fun configFor(symbol: Symbol): FlagStrategyConfig = effectiveConfigs[symbol] ?: FlagStrategyConfig.defaults()

    private fun subscribe(symbol: Symbol) {
        lastSubscribeAt[symbol] = Instant.now(clock)
        val aggregator = BarAggregator(symbol.value)
        val buffer = BarBuffer()

        aggregators[symbol] = aggregator
        buffers[symbol] = buffer

        val job =
            scope.launch {
                // Resolve this symbol's effective parameters first: the detector is built from them
                // and every entry filter below reads them, so nothing may observe the symbol before
                // they exist. Resolving here (not at construction) is what lets two symbols run the
                // same strategy with different tuning.
                val strategyConfig = resolveConfig(symbol)
                val detector = PatternDetector(symbol.value, buffer, strategyConfig)
                detectors[symbol] = detector

                // Bootstrap with historical 5-min bars and replay through detector
                runCatching {
                    val historical = equityHistoricalBarsPort.fetch5MinBars(symbol, strategyConfig.historicalBootstrapDays)
                    // Replay bars incrementally so the detector sees a growing/sliding window — exactly
                    // as it does live (see the streamBars collector below). Pre-filling the buffer with
                    // addAll() first makes every onNewBar() read the same full snapshot, so the same pole
                    // is re-detected on every bar and the state machine spins pointlessly through
                    // Idle → FlagpoleDetected → FlagForming → reset for each historical bar.
                    historical.forEach { bar ->
                        buffer.add(bar)
                        detector.onNewBar(bar)
                    }
                    logger.info {
                        "[${symbol.value}] Bootstrapped ${historical.size} historical bars — pattern state: ${detector.state.label()}"
                    }
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

                        // Read the detector and buffer from the maps rather than the locals captured
                        // when this job started: applying new parameters swaps both (see
                        // [rebuildDetector]) and a captured reference would keep feeding bars into
                        // the discarded detector — the change would appear to save and do nothing.
                        val liveDetector = detectors[symbol] ?: return@collect

                        // 1. Check for 5-min candle completion
                        val completed = aggregator.add(bar)
                        if (completed != null) {
                            barStorePort.writeBar(symbol, completed)
                            buffers[symbol]?.add(completed)
                            val state = liveDetector.onNewBar(completed)
                            if (state is PatternState.BreakoutReady) {
                                maybeEnter(symbol, state, "FIVE_MIN", completed.time)
                            }
                        }

                        // 2. Also check breakout on live 5-sec bar close (sub-candle precision).
                        // Read state snapshot first — checkBreakoutOnLiveBar is pure (no mutation).
                        val currentState = liveDetector.state
                        if (currentState is PatternState.FlagForming) {
                            val breakout = liveDetector.checkBreakoutOnLiveBar(bar.close, currentState.pole, currentState.flag)
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
            val strategyConfig = configFor(symbol)

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
            val stopLossPrice =
                BigDecimal(breakout.flag.lowestLow)
                    .subtract(BigDecimal("0.01"))
                    .setScale(2, RoundingMode.HALF_UP)

            val schedule = universePort.getMarketSchedule(symbol)
            val marketSession = schedule.session
            val zone = schedule.zone
            val closeTime = schedule.close
            val barZoned = barTime.atZone(zone)
            val minutesToClose =
                ChronoUnit.MINUTES
                    .between(barZoned.toLocalTime(), closeTime)
                    .toInt()
                    .coerceAtLeast(0)
            val flagAvgVolume =
                breakout.flag.bars
                    .map { it.volume }
                    .average()
                    .toLong()

            val bars = buffers[symbol]?.snapshot() ?: emptyList()
            val atrAtEntry = AtrCalculator.atr(bars, strategyConfig.atrPeriod).takeIf { !it.isNaN() }
            val volumeMaRaw = VolumeAnalysis.volumeMa(bars, strategyConfig.volumeMaPeriod).takeIf { !it.isNaN() }
            val volumeMaAtEntry = volumeMaRaw?.toLong()
            val flagpoleVolumeRatio =
                if (volumeMaRaw != null && volumeMaRaw > 0) {
                    breakout.pole.avgVolume / volumeMaRaw
                } else {
                    null
                }

            val sessionOpenLocal = schedule.open
            val sessionStart =
                barZoned
                    .toLocalDate()
                    .atTime(sessionOpenLocal)
                    .atZone(zone)
                    .toInstant()
            val todayBars = bars.filter { !it.time.isBefore(sessionStart) }
            val dayOpenPrice = todayBars.firstOrNull()?.open
            val vwapAtEntry =
                if (todayBars.isNotEmpty()) {
                    val totalVol = todayBars.sumOf { it.volume.toDouble() }
                    if (totalVol > 0) {
                        todayBars.sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume } / totalVol
                    } else {
                        null
                    }
                } else {
                    null
                }

            // Quality filters
            val minutesSinceOpen =
                ChronoUnit.MINUTES
                    .between(sessionStart, barTime)
                    .toInt()
                    .coerceAtLeast(0)
            if (minutesSinceOpen < strategyConfig.skipFirstRthMinutes) {
                logger.debug {
                    "[${symbol.value}] Entry skipped — ${minutesSinceOpen}min since open < skipFirstRthMinutes(${strategyConfig.skipFirstRthMinutes})"
                }
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
                    logger.debug {
                        "[${symbol.value}] Entry skipped — pole/ATR ratio $poleAtrRatio < min(${strategyConfig.minFlagpoleAtrMultiple})"
                    }
                    detectors[symbol]?.reset()
                    return@launch
                }
                if (poleAtrRatio > strategyConfig.maxFlagpoleAtrMultiple) {
                    logger.debug {
                        "[${symbol.value}] Entry skipped — pole/ATR ratio $poleAtrRatio > max(${strategyConfig.maxFlagpoleAtrMultiple})"
                    }
                    detectors[symbol]?.reset()
                    return@launch
                }
            }
            val retracementPct = Math.abs(breakout.flag.retracement)
            if (retracementPct < strategyConfig.minFlagRetracementPct) {
                logger.debug {
                    "[${symbol.value}] Entry skipped — retracement ${retracementPct * 100}% < min(${strategyConfig.minFlagRetracementPct * 100}%)"
                }
                detectors[symbol]?.reset()
                return@launch
            }
            if (breakout.flag.bars.size < strategyConfig.minFlagBarsForEntry) {
                logger.debug {
                    "[${symbol.value}] Entry skipped — flag bars ${breakout.flag.bars.size} < min(${strategyConfig.minFlagBarsForEntry})"
                }
                detectors[symbol]?.reset()
                return@launch
            }

            val stopDistancePct =
                entryPrice
                    .subtract(stopLossPrice)
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

            // Per-symbol serialization: prevent concurrent entries for same symbol.
            // This ensures no two breakout signals on the same symbol execute simultaneously.
            symbolMutexManager.withSymbolLock(symbol) {
                // Serialise position count check + submission to close the TOCTOU window where two
                // concurrent breakout signals could both read below maxOpenPositions before either persists.
                entryMutex.withLock {
                    val active = flagPort.findByStatuses(FlagStatus.ACTIVE_STATUSES)
                    // One position per symbol at a time: a re-formed pattern can fire a second
                    // legitimate breakout minutes after the first (AAPL 2026-06-26 double-open),
                    // and two live positions on one symbol make broker-position attribution
                    // ambiguous for close/recovery logic.
                    if (active.any { it.symbol == symbol }) {
                        logger.info {
                            "[${symbol.value}] A PENDING/OPEN/CLOSING position already exists for this symbol — skipping duplicate entry"
                        }
                        detectors[symbol]?.reset()
                        return@withLock
                    }
                    if (active.size >= config.maxOpenPositions) {
                        logger.info { "[${symbol.value}] Max open positions (${config.maxOpenPositions}) reached — skipping" }
                        detectors[symbol]?.reset()
                        return@withLock
                    }
                    detectors[symbol]?.reset() // prevent double-firing
                    flagExecutionService.execute(request)
                }
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
        val lastCandleAt: Instant?,
        val patternState: String,
        val poleHeightPct: Double?,
        val flagBars: Int?,
        val flagRetracementPct: Double?,
        val dataFeed: String,
        /** True when this symbol runs parameters that differ from the saved global baseline. */
        val customParams: Boolean,
        /** Only the differing parameters, as `name -> "custom (default X)"`, for the hover detail. */
        val customParamDetail: Map<String, String>,
    )

    suspend fun getScannerStatus(): List<SymbolScannerStatus> {
        // One query for the whole table rather than one per row: the Candle Scanner polls this every
        // 10 seconds and a per-symbol lookup would be 40 round trips a poll.
        val overrides =
            runCatching { paramsResolver.symbolsWithOverrides(FLAG_STRATEGY_ID) }
                .getOrElse { e ->
                    logger.warn { "Could not read flag param overrides for scanner status: ${e.message}" }
                    emptyMap()
                }
        return subscriptions.keys
            .map { symbol ->
                val symbolOverrides = overrides[symbol.value].orEmpty()
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
                    dataFeed = marketDataTypeTracker.feedFor(symbol).name,
                    customParams = symbolOverrides.isNotEmpty(),
                    customParamDetail =
                        symbolOverrides.mapValues { (_, o) -> "${o.custom} (default ${o.inherited})" },
                )
            }.sortedBy { it.symbol }
    }

    private fun PatternState.label(): String =
        when (this) {
            is PatternState.Idle -> "Idle"
            is PatternState.FlagpoleDetected -> "Pole ($consolidationBars consol. bars)"
            is PatternState.FlagForming -> "Flag forming (${flag.bars.size} bars)"
            is PatternState.BreakoutReady -> "BREAKOUT READY"
        }

    private fun PatternState.pole(): Flagpole? =
        when (this) {
            is PatternState.FlagpoleDetected -> pole
            is PatternState.FlagForming -> pole
            is PatternState.BreakoutReady -> pole
            else -> null
        }

    private fun PatternState.flag(): Flag? =
        when (this) {
            is PatternState.FlagForming -> flag
            is PatternState.BreakoutReady -> flag
            else -> null
        }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}
