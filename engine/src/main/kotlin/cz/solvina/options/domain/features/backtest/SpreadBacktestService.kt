package cz.solvina.options.domain.features.backtest

import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.market.BlackScholes
import cz.solvina.options.domain.features.volatility.HistoricalDataPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor

private val logger = KotlinLogging.logger {}

/**
 * Research backtest for bull-put spreads. Reconstructs the option chain with Black-Scholes from the
 * underlying's daily close (stored 5-min bars aggregated to daily) and its historical
 * OPTION_IMPLIED_VOLATILITY — the same IV series the live IV-rank uses, so the model only ever sees
 * data available on the simulated day.
 *
 * DELIBERATELY approximate: flat IV surface (no skew), a modeled bid/ask, no real fills. So the
 * dollar P&L is optimistic and NOT a trustworthy edge figure — but the *structural* outputs
 * (win rate, breach rate, exit-reason mix) are reliable enough to compare entry-criteria variants
 * (IV-rank floor, target delta, DTE, TP/SL). Change criteria on this, not on a week of live trades.
 */
@Service
class SpreadBacktestService(
    private val barStore: BarStorePort,
    private val histDataPort: HistoricalDataPort,
    private val clock: Clock,
) {
    data class Params(
        val ivRankThreshold: Double = 45.0,
        val targetDelta: Double = 0.30,
        val spreadWidth: Double = 5.0,
        val dte: Int = 45,
        val takeProfitPct: Double = 0.50,
        val stopLossMultiple: Double = 2.0,
        val timeExitDte: Int = 21,
        val minCredit: Double = 0.35,
        val ivHistoryDays: Int = 365,
        val riskFreeRate: Double = 0.05,
    )

    data class Trade(
        val symbol: String,
        val entryDate: LocalDate,
        val exitDate: LocalDate,
        val exitReason: String,
        val shortStrike: Double,
        val longStrike: Double,
        val entryCredit: Double,
        val pnlPerShare: Double,
        val breached: Boolean,
        val daysHeld: Int,
        val ivRankAtEntry: Double,
    )

    data class Result(
        val symbols: List<String>,
        val from: LocalDate,
        val to: LocalDate,
        val tradeCount: Int,
        val winRate: Double,
        val breachRate: Double,
        val totalPnl: Double,
        val avgPnlPerTrade: Double,
        val avgDaysHeld: Double,
        val byExitReason: Map<String, Int>,
        val trades: List<Trade>,
    )

    suspend fun run(
        symbols: List<String>,
        from: LocalDate,
        to: LocalDate,
        params: Params,
    ): Result {
        val trades =
            symbols.flatMap { s ->
                runCatching { runSymbol(Symbol(s.trim().uppercase()), from, to, params) }
                    .getOrElse { e ->
                        logger.warn { "[$s] spread backtest failed: ${e.message}" }
                        emptyList()
                    }
            }
        val n = trades.size
        val wins = trades.count { it.pnlPerShare > 0 }
        val breaches = trades.count { it.breached }
        val total = trades.sumOf { it.pnlPerShare } * 100.0
        return Result(
            symbols = symbols,
            from = from,
            to = to,
            tradeCount = n,
            winRate = if (n == 0) 0.0 else wins.toDouble() / n,
            breachRate = if (n == 0) 0.0 else breaches.toDouble() / n,
            totalPnl = total,
            avgPnlPerTrade = if (n == 0) 0.0 else total / n,
            avgDaysHeld = if (n == 0) 0.0 else trades.map { it.daysHeld }.average(),
            byExitReason = trades.groupingBy { it.exitReason }.eachCount(),
            trades = trades,
        )
    }

    private class OpenPos(
        val symbol: Symbol,
        val entryDate: LocalDate,
        val expiry: LocalDate,
        val shortStrike: Double,
        val longStrike: Double,
        val credit: Double,
        val ivRankAtEntry: Double,
    ) {
        var breached = false
    }

    private suspend fun runSymbol(
        symbol: Symbol,
        from: LocalDate,
        to: LocalDate,
        p: Params,
    ): List<Trade> {
        // Daily close from stored 5-min bars.
        val fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant()
        val toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val dailyClose =
            barStore
                .readBars(symbol, fromInstant, toInstant)
                .groupBy { it.time.atZone(ZoneOffset.UTC).toLocalDate() }
                .mapValues { (_, bars) -> bars.maxByOrNull { it.time }!!.close }

        // Daily IV history (covers the trailing IV-rank window before `from`).
        val daysToFetch =
            ChronoUnit.DAYS
                .between(from.minusDays(p.ivHistoryDays.toLong()), LocalDate.now(clock))
                .toInt()
                .coerceAtLeast(p.ivHistoryDays)
        val ivByDate =
            histDataPort
                .fetchDailyBars(symbol, daysToFetch)
                .toList()
                .filter { it.iv != null }
                .associate { it.date to it.iv!! }

        if (dailyClose.isEmpty() || ivByDate.size < 2) return emptyList()
        val ivDates = ivByDate.keys.sorted()

        val tradingDays =
            dailyClose.keys
                .filter { ivByDate.containsKey(it) && !it.isBefore(from) && !it.isAfter(to) }
                .sorted()

        val trades = mutableListOf<Trade>()
        var open: OpenPos? = null

        for (date in tradingDays) {
            val spot = dailyClose[date]!!
            val iv = ivByDate[date]!!

            // Manage an open position.
            open?.let { pos ->
                if (spot < pos.shortStrike) pos.breached = true
                val remDays = ChronoUnit.DAYS.between(date, pos.expiry).toInt()
                val t = remDays.coerceAtLeast(0) / 365.0
                val spreadVal =
                    (
                        BlackScholes.putPrice(spot, pos.shortStrike, t, p.riskFreeRate, iv) -
                            BlackScholes.putPrice(spot, pos.longStrike, t, p.riskFreeRate, iv)
                    ).coerceIn(0.0, p.spreadWidth)
                val loss = spreadVal - pos.credit
                val exit =
                    when {
                        spreadVal <= pos.credit * (1 - p.takeProfitPct) -> "TAKE_PROFIT"
                        loss >= p.stopLossMultiple * pos.credit -> "STOP_LOSS"
                        remDays <= p.timeExitDte -> "TIME_EXIT"
                        else -> null
                    }
                if (exit != null) {
                    val pnl = (pos.credit - spreadVal).coerceAtLeast(-(p.spreadWidth - pos.credit))
                    trades +=
                        Trade(
                            symbol = symbol.value,
                            entryDate = pos.entryDate,
                            exitDate = date,
                            exitReason = exit,
                            shortStrike = pos.shortStrike,
                            longStrike = pos.longStrike,
                            entryCredit = pos.credit,
                            pnlPerShare = pnl,
                            breached = pos.breached,
                            daysHeld = ChronoUnit.DAYS.between(pos.entryDate, date).toInt(),
                            ivRankAtEntry = pos.ivRankAtEntry,
                        )
                    open = null
                }
            }

            // Consider a new entry.
            if (open == null) {
                val ivRank = ivRankAt(date, ivByDate, ivDates, p.ivHistoryDays) ?: continue
                if (ivRank < p.ivRankThreshold) continue
                val t = p.dte / 365.0
                val shortStrike = findShortStrike(spot, iv, t, p) ?: continue
                val longStrike = shortStrike - p.spreadWidth
                if (longStrike <= 0) continue
                val credit =
                    BlackScholes.putPrice(spot, shortStrike, t, p.riskFreeRate, iv) -
                        BlackScholes.putPrice(spot, longStrike, t, p.riskFreeRate, iv)
                if (credit < p.minCredit) continue
                open =
                    OpenPos(
                        symbol = symbol,
                        entryDate = date,
                        expiry = date.plusDays(p.dte.toLong()),
                        shortStrike = shortStrike,
                        longStrike = longStrike,
                        credit = credit,
                        ivRankAtEntry = ivRank,
                    )
            }
        }
        return trades
    }

    /** Search a $1 strike grid below spot for the put closest to [Params.targetDelta] (abs). */
    private fun findShortStrike(
        spot: Double,
        iv: Double,
        t: Double,
        p: Params,
    ): Double? {
        var best: Double? = null
        var bestErr = Double.MAX_VALUE
        var k = floor(spot).toInt()
        val floorStrike = (spot * 0.5).toInt().coerceAtLeast(1)
        while (k >= floorStrike) {
            val absDelta = abs(BlackScholes.putDelta(spot, k.toDouble(), t, p.riskFreeRate, iv))
            val err = abs(absDelta - p.targetDelta)
            if (err < bestErr) {
                bestErr = err
                best = k.toDouble()
            }
            k -= 1
        }
        return best
    }

    private fun ivRankAt(
        date: LocalDate,
        ivByDate: Map<LocalDate, Double>,
        ivDates: List<LocalDate>,
        windowDays: Int,
    ): Double? {
        val current = ivByDate[date] ?: return null
        val windowStart = date.minusDays(windowDays.toLong())
        val window = ivDates.filter { !it.isBefore(windowStart) && !it.isAfter(date) }.mapNotNull { ivByDate[it] }
        if (window.size < 2) return null
        val mn = window.min()
        val mx = window.max()
        return if (mx == mn) 50.0 else (current - mn) / (mx - mn) * 100.0
    }
}
