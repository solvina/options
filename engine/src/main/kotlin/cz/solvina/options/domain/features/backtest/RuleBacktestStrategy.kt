package cz.solvina.options.domain.features.backtest

import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.math.max

/**
 * A parameter-driven [BacktestableStrategy]: long-on-support-bounce, defined entirely by [Params]
 * (SMA 50/200 trend + support, RSI + RSI-slope for the bounce). It is deliberately *one
 * implementation* of the lifecycle interface — a promising parameter set can later graduate into a
 * hand-written / scripted strategy over the same seam (onBar → future onCandle/onTick + context).
 *
 * No look-ahead: every indicator is updated from the current bar's close only, and the engine fills
 * the emitted bracket on the *next* bar's open. Exits (stop/target) are simulated by the engine.
 */
class RuleBacktestStrategy(
    private val p: Params,
) : BacktestableStrategy {
    data class Params(
        val rsiPeriod: Int = 14,
        val rsiOversold: Double = 40.0,
        val requireRsiRising: Boolean = true,
        val smaFastPeriod: Int = 50,
        val smaSlowPeriod: Int = 200,
        val requireUptrend: Boolean = true, // close > slow SMA (200)
        val supportProximityPct: Double = 3.0, // close within this % above the fast SMA (support)
        val stopLossPct: Double = 3.0,
        val targetPct: Double = 6.0,
        val riskPerTrade: Double = 200.0,
        val maxOpenPositions: Int = 1,
    )

    data class RuleTrade(
        val symbol: String,
        val entryAt: Instant,
        val entryPrice: BigDecimal,
        val exitAt: Instant,
        val exitPrice: BigDecimal,
        val closeReason: String,
        val shares: Int,
        val pnl: BigDecimal,
    )

    private val ind = mutableMapOf<Symbol, Indicators>()
    private val pending = mutableMapOf<String, PendingInfo>() // tradeId → symbol+shares, set at emit
    private val open = mutableMapOf<String, OpenTrade>()
    private val completed = mutableListOf<RuleTrade>()
    private val counter = AtomicInteger(0)

    private data class PendingInfo(
        val symbol: Symbol,
        val shares: Int,
    )

    private data class OpenTrade(
        val symbol: Symbol,
        val entryAt: Instant,
        val entryPrice: BigDecimal,
        val shares: Int,
    )

    override fun initialize(
        symbols: List<Symbol>,
        warmupBars: Map<Symbol, List<FiveMinuteBar>>,
    ) {
        for (symbol in symbols) {
            val i = Indicators(p.rsiPeriod)
            warmupBars[symbol]?.forEach { i.update(it.close) }
            ind[symbol] = i
        }
    }

    override fun onBar(
        symbol: Symbol,
        bar: FiveMinuteBar,
        account: BacktestAccountView,
    ): List<BacktestSignal> {
        val i = ind.getOrPut(symbol) { Indicators(p.rsiPeriod) }
        i.update(bar.close)

        if (account.openPositions + account.pendingPositions >= p.maxOpenPositions) return emptyList()

        val rsi = i.rsi ?: return emptyList()
        val smaFast = i.sma(p.smaFastPeriod) ?: return emptyList()
        val smaSlow = i.sma(p.smaSlowPeriod) ?: return emptyList()
        val close = bar.close

        // Support-bounce, long: (optional) uptrend, price near/above the fast SMA (support),
        // RSI oversold and (optionally) turning up.
        val uptrendOk = !p.requireUptrend || close > smaSlow
        val nearSupport = close >= smaFast && close <= smaFast * (1.0 + p.supportProximityPct / 100.0)
        val rsiOk = rsi < p.rsiOversold
        val risingOk = !p.requireRsiRising || (i.prevRsi != null && rsi > i.prevRsi!!)
        if (!(uptrendOk && nearSupport && rsiOk && risingOk)) return emptyList()

        val entry = close
        val stop = entry * (1.0 - p.stopLossPct / 100.0)
        val target = entry * (1.0 + p.targetPct / 100.0)
        val perShareRisk = entry - stop
        if (perShareRisk <= 0.0) return emptyList()
        val shares = floor(p.riskPerTrade / perShareRisk).toInt()
        if (shares <= 0) return emptyList()

        val tradeId = "rule-${counter.incrementAndGet()}"
        pending[tradeId] = PendingInfo(symbol, shares)
        return listOf(
            BacktestSignal.OpenBracket(
                tradeId = tradeId,
                symbol = symbol,
                shares = shares,
                entryPrice = BigDecimal(entry).setScale(2, RoundingMode.HALF_UP),
                stopLossPrice = BigDecimal(stop).setScale(2, RoundingMode.HALF_UP),
                profitTargetPrice = BigDecimal(target).setScale(2, RoundingMode.HALF_UP),
            ),
        )
    }

    override fun onEntryFilled(
        tradeId: String,
        fillPrice: BigDecimal,
        filledAt: Instant,
    ) {
        val info = pending.remove(tradeId) ?: return
        open[tradeId] = OpenTrade(info.symbol, filledAt, fillPrice, info.shares)
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
        val o = open.remove(tradeId) ?: return
        completed +=
            RuleTrade(
                symbol = o.symbol.value,
                entryAt = o.entryAt,
                entryPrice = o.entryPrice,
                exitAt = closedAt,
                exitPrice = closePrice,
                closeReason = closeReason,
                shares = o.shares,
                pnl = closePrice.subtract(o.entryPrice).multiply(BigDecimal(o.shares)),
            )
    }

    override fun trades(): List<RuleTrade> = completed

    /** Per-symbol rolling indicator state: SMA (any period) + Wilder RSI + RSI slope. */
    private class Indicators(
        private val rsiPeriod: Int,
    ) {
        private val closes = ArrayDeque<Double>()
        private var prevClose: Double? = null
        private var avgGain = 0.0
        private var avgLoss = 0.0
        private var seededCount = 0
        private var seeded = false
        var rsi: Double? = null
            private set
        var prevRsi: Double? = null
            private set

        fun update(close: Double) {
            closes.addLast(close)
            if (closes.size > 400) closes.removeFirst()
            val prev = prevClose
            if (prev != null) {
                val delta = close - prev
                val gain = max(delta, 0.0)
                val loss = max(-delta, 0.0)
                if (!seeded) {
                    avgGain += gain
                    avgLoss += loss
                    seededCount++
                    if (seededCount == rsiPeriod) {
                        avgGain /= rsiPeriod
                        avgLoss /= rsiPeriod
                        seeded = true
                    }
                } else {
                    avgGain = (avgGain * (rsiPeriod - 1) + gain) / rsiPeriod
                    avgLoss = (avgLoss * (rsiPeriod - 1) + loss) / rsiPeriod
                }
                if (seeded) {
                    prevRsi = rsi
                    rsi = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
                }
            }
            prevClose = close
        }

        fun sma(period: Int): Double? = if (closes.size < period) null else closes.toList().takeLast(period).average()
    }
}
