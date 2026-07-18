package cz.solvina.options.domain.features.backtest

import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private val logger = KotlinLogging.logger {}

class BacktestEngine(
    private val barStore: BarStorePort,
) {
    data class Request(
        val symbols: List<Symbol>,
        val from: LocalDate,
        val to: LocalDate,
        val initialCapital: BigDecimal = BigDecimal("20000"),
        val maxOpenPositions: Int = 3,
        /** Calendar days before [from] to warm up the pattern detector. */
        val warmupDays: Long = 10L,
        /** When true, positions are NOT force-closed at day end — they hold until stop/target hit
         *  (multi-day swing), instead of the default intraday EOD liquidation. */
        val holdOvernight: Boolean = false,
        /** When set, exits use a trailing stop [this]×R below the running peak (no fixed target) —
         *  rides the move and exits on pullback. Typically combined with holdOvernight. */
        val trailStopRMultiple: Double? = null,
        /** Detection/exit timeframe in minutes. Stored data is 5-min; 10/15/… are aggregated from it
         *  (exact, since they're multiples). Must be a multiple of 5. Only applies when
         *  [timeframe] is FIVE_MIN. */
        val barMinutes: Int = 5,
        /** Bar timeframe read from the store. FIVE_MIN → intraday (optionally aggregated to
         *  [barMinutes]); DAILY / FOUR_HOUR are read natively (no aggregation). */
        val timeframe: Timeframe = Timeframe.FIVE_MIN,
    )

    data class Summary(
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
    )

    data class Result<T>(
        val summary: Summary,
        val trades: List<T>,
    )

    private data class PendingEntry(
        val signal: BacktestSignal.OpenBracket,
        /** Trading day the signal was emitted — governs how long an unfilled entry stays alive. */
        val emittedOn: LocalDate,
    )

    private data class OpenPosition(
        val signal: BacktestSignal.OpenBracket,
        val actualEntryPrice: BigDecimal,
        var highestSeen: BigDecimal,
        var lowestSeen: BigDecimal,
    )

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> run(
        request: Request,
        strategy: BacktestableStrategy,
    ): Result<T> {
        val nyZone = ZoneId.of("America/New_York")
        val warmupFrom = request.from.minusDays(request.warmupDays)

        // Fetch full range (warmup + backtest) for all symbols
        val allBars = mutableMapOf<Symbol, List<FiveMinuteBar>>()
        for (symbol in request.symbols) {
            val fromInstant = warmupFrom.atStartOfDay(ZoneOffset.UTC).toInstant()
            val toInstant =
                request.to
                    .plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
            val raw = barStore.readBars(symbol, fromInstant, toInstant, request.timeframe)
            // Daily / 4h are stored natively; only 5-min is aggregated up to barMinutes.
            val bars = if (request.timeframe == Timeframe.FIVE_MIN) aggregateBars(raw, request.barMinutes) else raw
            logger.info {
                "[${symbol.value}] Loaded ${raw.size} ${request.timeframe.label} bars → ${bars.size} bars"
            }
            allBars[symbol] = bars
        }

        // Split into warmup bars (before backtest start) and backtest bars
        val warmupBars =
            allBars.mapValues { (_, bars) ->
                bars.filter {
                    it.time
                        .atZone(nyZone)
                        .toLocalDate()
                        .isBefore(request.from)
                }
            }
        val backtestBars =
            allBars.mapValues { (_, bars) ->
                bars.filter {
                    !it.time
                        .atZone(nyZone)
                        .toLocalDate()
                        .isBefore(request.from)
                }
            }

        strategy.initialize(request.symbols, warmupBars)

        // Merge all backtest bars, sort chronologically, group by trading day
        val timeline =
            backtestBars
                .flatMap { (sym, bars) -> bars.map { sym to it } }
                .sortedBy { it.second.time }

        val byDay =
            timeline
                .groupBy { (_, bar) -> bar.time.atZone(nyZone).toLocalDate() }
                .entries
                .sortedBy { it.key }

        // Account state
        var capital = request.initialCapital
        var peakCapital = capital
        var maxDrawdown = BigDecimal.ZERO
        val pending = mutableListOf<PendingEntry>()
        val open = mutableListOf<OpenPosition>()

        // Trade statistics — winCount=profit_target, lossCount=stop_loss, eodCount=eod_liquidation
        var winCount = 0
        var lossCount = 0
        var eodCount = 0
        var totalWinPnl = BigDecimal.ZERO
        var totalLossPnl = BigDecimal.ZERO
        val rList = mutableListOf<BigDecimal>()
        val winRList = mutableListOf<BigDecimal>()
        val lossRList = mutableListOf<BigDecimal>()

        for ((day, dayBars) in byDay) {
            val sorted = dayBars.sortedBy { it.second.time }
            val lastBarBySymbol = mutableMapOf<Symbol, FiveMinuteBar>()

            for ((symbol, bar) in sorted) {
                lastBarBySymbol[symbol] = bar

                // 1. Try to fill pending entries
                val fillable = pending.filter { it.signal.symbol == symbol }
                for (pe in fillable) {
                    val fillPrice = simulateEntry(bar, pe.signal) ?: continue
                    pending.remove(pe)
                    open.add(
                        OpenPosition(
                            pe.signal,
                            fillPrice,
                            BigDecimal.valueOf(bar.high),
                            BigDecimal.valueOf(bar.low),
                        ),
                    )
                    strategy.onEntryFilled(pe.signal.tradeId, fillPrice, bar.time)
                }

                // 2. Check exits for open positions
                val toClose = mutableListOf<OpenPosition>()
                for (op in open.filter { it.signal.symbol == symbol }) {
                    // Peak through the PREVIOUS bar — the trailing stop on this bar trails off that,
                    // not this bar's own high (avoids within-bar lookahead).
                    val peakBeforeBar = op.highestSeen
                    op.highestSeen = op.highestSeen.max(BigDecimal.valueOf(bar.high))
                    op.lowestSeen = op.lowestSeen.min(BigDecimal.valueOf(bar.low))

                    val (closePrice, reason) = simulateExit(bar, op, peakBeforeBar, request.trailStopRMultiple) ?: continue
                    val pnl =
                        closePrice
                            .subtract(op.actualEntryPrice)
                            .multiply(BigDecimal(op.signal.shares))
                            .setScale(2, RoundingMode.HALF_UP)
                    capital = capital.add(pnl)
                    val (newPeak, drawdown) = updateDrawdown(capital, peakCapital)
                    peakCapital = newPeak
                    if (drawdown > maxDrawdown) maxDrawdown = drawdown
                    recordRMultiple(pnl, op, rList, winRList, lossRList)
                    if (pnl >= BigDecimal.ZERO) {
                        totalWinPnl = totalWinPnl.add(pnl)
                    } else {
                        totalLossPnl = totalLossPnl.add(pnl.abs())
                    }
                    when (reason) {
                        "profit_target" -> winCount++
                        "stop_loss" -> lossCount++
                        "eod_liquidation" -> eodCount++
                        "trailing_stop" -> if (pnl >= BigDecimal.ZERO) winCount++ else lossCount++
                    }
                    strategy.onPositionClosed(op.signal.tradeId, closePrice, reason, bar.time, op.highestSeen, op.lowestSeen)
                    toClose.add(op)
                }
                open.removeAll(toClose)

                // 3. Ask strategy for new signals
                val view = BacktestAccountView(capital, open.size, pending.size)
                for (signal in strategy.onBar(symbol, bar, view)) {
                    if (signal is BacktestSignal.OpenBracket &&
                        open.size + pending.size < request.maxOpenPositions
                    ) {
                        pending.add(PendingEntry(signal, day))
                    }
                }
            }

            // EOD: liquidate all remaining open positions at last-bar close — unless holdOvernight,
            // in which case positions carry to the next day and exit only on stop/target.
            if (!request.holdOvernight) {
                for (op in open.toList()) {
                    val lastBar = lastBarBySymbol[op.signal.symbol] ?: continue
                    op.highestSeen = op.highestSeen.max(BigDecimal.valueOf(lastBar.high))
                    op.lowestSeen = op.lowestSeen.min(BigDecimal.valueOf(lastBar.low))
                    val closePrice = BigDecimal.valueOf(lastBar.close)
                    val pnl =
                        closePrice
                            .subtract(op.actualEntryPrice)
                            .multiply(BigDecimal(op.signal.shares))
                            .setScale(2, RoundingMode.HALF_UP)
                    capital = capital.add(pnl)
                    val (newPeak, drawdown) = updateDrawdown(capital, peakCapital)
                    peakCapital = newPeak
                    if (drawdown > maxDrawdown) maxDrawdown = drawdown
                    eodCount++
                    recordRMultiple(pnl, op, rList, winRList, lossRList)
                    if (pnl >= BigDecimal.ZERO) {
                        totalWinPnl = totalWinPnl.add(pnl)
                    } else {
                        totalLossPnl = totalLossPnl.add(pnl.abs())
                    }
                    strategy.onPositionClosed(op.signal.tradeId, closePrice, "eod_liquidation", lastBar.time, op.highestSeen, op.lowestSeen)
                }
                open.clear()
            }

            // Expire unfilled pending entries. Intraday (!holdOvernight): entries are good for the
            // emitting day only (breakouts don't carry over). Swing (holdOvernight): a signal is
            // good through the NEXT session — the engine fills on the next bar, and on the daily
            // timeframe that bar is tomorrow's, so clearing nightly meant a daily backtest could
            // never fill a single entry.
            val expired =
                if (request.holdOvernight) pending.filter { it.emittedOn.isBefore(day) } else pending.toList()
            expired.forEach { strategy.onEntryExpired(it.signal.tradeId) }
            pending.removeAll(expired)
        }

        // holdOvernight: mark-to-market any positions still open at the end of the backtest window.
        if (request.holdOvernight) {
            for (op in open.toList()) {
                val lastBar = backtestBars[op.signal.symbol]?.lastOrNull() ?: continue
                op.highestSeen = op.highestSeen.max(BigDecimal.valueOf(lastBar.high))
                op.lowestSeen = op.lowestSeen.min(BigDecimal.valueOf(lastBar.low))
                val closePrice = BigDecimal.valueOf(lastBar.close)
                val pnl =
                    closePrice
                        .subtract(op.actualEntryPrice)
                        .multiply(BigDecimal(op.signal.shares))
                        .setScale(2, RoundingMode.HALF_UP)
                capital = capital.add(pnl)
                val (newPeak, drawdown) = updateDrawdown(capital, peakCapital)
                peakCapital = newPeak
                if (drawdown > maxDrawdown) maxDrawdown = drawdown
                eodCount++
                recordRMultiple(pnl, op, rList, winRList, lossRList)
                if (pnl >= BigDecimal.ZERO) totalWinPnl = totalWinPnl.add(pnl) else totalLossPnl = totalLossPnl.add(pnl.abs())
                strategy.onPositionClosed(op.signal.tradeId, closePrice, "eod_liquidation", lastBar.time, op.highestSeen, op.lowestSeen)
            }
            open.clear()
        }

        val totalPnl = capital.subtract(request.initialCapital)
        val totalPnlPct =
            if (request.initialCapital > BigDecimal.ZERO) {
                totalPnl
                    .divide(request.initialCapital, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100"))
                    .setScale(2, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }

        // winRate excludes EOD trades (matches production analytics convention)
        val winRate = if (winCount + lossCount > 0) winCount.toDouble() / (winCount + lossCount) else 0.0

        return Result(
            summary =
                Summary(
                    symbols = request.symbols.map { it.value },
                    from = request.from,
                    to = request.to,
                    initialCapital = request.initialCapital,
                    finalCapital = capital,
                    totalPnl = totalPnl,
                    totalPnlPct = totalPnlPct,
                    tradeCount = winCount + lossCount + eodCount,
                    winCount = winCount,
                    lossCount = lossCount,
                    eodCount = eodCount,
                    winRate = winRate,
                    avgRMultiple = rList.avg(),
                    avgWinR = winRList.avg(),
                    avgLossR = lossRList.avg(),
                    profitFactor =
                        if (totalLossPnl > BigDecimal.ZERO) {
                            totalWinPnl.divide(totalLossPnl, 2, RoundingMode.HALF_UP)
                        } else if (totalWinPnl > BigDecimal.ZERO) {
                            BigDecimal("999.99")
                        } else {
                            null
                        },
                    maxDrawdownPct = maxDrawdown.multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP),
                ),
            trades = strategy.trades() as List<T>,
        )
    }

    // -------------------------------------------------------------------------
    // Fill simulation
    // -------------------------------------------------------------------------

    /** Aggregates 5-min bars into clock-aligned [barMinutes]-minute OHLCV bars (no-op if 5).
     *  10/15/… min are exact multiples of 5-min, so this is lossless vs natively-fetched bars. */
    private fun aggregateBars(
        bars: List<FiveMinuteBar>,
        barMinutes: Int,
    ): List<FiveMinuteBar> {
        if (barMinutes <= 5 || bars.isEmpty()) return bars
        val bucketSec = barMinutes * 60L
        return bars
            .groupBy { it.time.epochSecond / bucketSec }
            .toSortedMap()
            .map { (bucket, group) ->
                val sorted = group.sortedBy { it.time }
                FiveMinuteBar(
                    time = Instant.ofEpochSecond(bucket * bucketSec),
                    open = sorted.first().open,
                    high = sorted.maxOf { it.high },
                    low = sorted.minOf { it.low },
                    close = sorted.last().close,
                    volume = sorted.sumOf { it.volume },
                )
            }
    }

    private fun simulateEntry(
        bar: FiveMinuteBar,
        signal: BacktestSignal.OpenBracket,
    ): BigDecimal? {
        val ep = signal.entryPrice.toDouble()
        val pt = signal.profitTargetPrice.toDouble()
        val fillPrice =
            when {
                bar.open >= ep -> bar.open // gap-up: fill at open
                bar.high >= ep -> ep // bar reaches entry level
                else -> return null
            }
        // Skip entry if fill is already at or beyond profit target (would be a wash or instant loss)
        return if (fillPrice >= pt) null else BigDecimal.valueOf(fillPrice)
    }

    private fun simulateExit(
        bar: FiveMinuteBar,
        op: OpenPosition,
        peakBeforeBar: BigDecimal,
        trailStopRMultiple: Double?,
    ): Pair<BigDecimal, String>? {
        val sl = op.signal.stopLossPrice.toDouble()

        // Trailing-stop mode: no fixed target — exit when price pulls back to [trail]×R below the
        // running peak. The trail never sits below the initial stop.
        if (trailStopRMultiple != null) {
            val riskPerShare = op.signal.entryPrice.toDouble() - sl
            val trailStop = maxOf(sl, peakBeforeBar.toDouble() - trailStopRMultiple * riskPerShare)
            return when {
                bar.open <= trailStop -> BigDecimal.valueOf(bar.open) to "trailing_stop" // gap below
                bar.low <= trailStop -> BigDecimal.valueOf(trailStop).setScale(2, RoundingMode.HALF_UP) to "trailing_stop"
                else -> null
            }
        }

        val pt = op.signal.profitTargetPrice.toDouble()
        return when {
            bar.open <= sl -> BigDecimal.valueOf(bar.open) to "stop_loss" // gap-down through stop
            bar.open >= pt -> BigDecimal.valueOf(bar.open) to "profit_target" // gap-up through PT
            bar.low <= sl && bar.high >= pt -> op.signal.stopLossPrice to "stop_loss" // both hit — conservative
            bar.low <= sl -> op.signal.stopLossPrice to "stop_loss"
            bar.high >= pt -> op.signal.profitTargetPrice to "profit_target"
            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun updateDrawdown(
        capital: BigDecimal,
        peak: BigDecimal,
    ): Pair<BigDecimal, BigDecimal> {
        val newPeak = if (capital > peak) capital else peak
        val dd =
            if (newPeak > BigDecimal.ZERO) {
                newPeak.subtract(capital).divide(newPeak, 6, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }
        return newPeak to dd
    }

    private fun recordRMultiple(
        pnl: BigDecimal,
        op: OpenPosition,
        rList: MutableList<BigDecimal>,
        winRList: MutableList<BigDecimal>,
        lossRList: MutableList<BigDecimal>,
    ) {
        val riskAmount =
            op.signal.entryPrice
                .subtract(op.signal.stopLossPrice)
                .abs()
                .multiply(BigDecimal(op.signal.shares))
        if (riskAmount <= BigDecimal.ZERO) return
        val r = pnl.divide(riskAmount, 2, RoundingMode.HALF_UP)
        rList.add(r)
        if (pnl >= BigDecimal.ZERO) winRList.add(r) else lossRList.add(r)
    }

    private fun List<BigDecimal>.avg(): BigDecimal? {
        if (isEmpty()) return null
        return fold(BigDecimal.ZERO) { a, v -> a.add(v) }
            .divide(BigDecimal(size), 2, RoundingMode.HALF_UP)
    }
}
