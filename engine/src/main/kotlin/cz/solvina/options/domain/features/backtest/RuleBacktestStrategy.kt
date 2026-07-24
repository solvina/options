package cz.solvina.options.domain.features.backtest

import cz.solvina.options.domain.features.bars.AtrCalculator
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
        /** Wilder ATR lookback (bars of the backtest timeframe) for the ATR-based exits below. */
        val atrPeriod: Int = 14,
        /** When > 0, stop = entry − ATR × this (volatility-scaled), overriding [stopLossPct]. */
        val stopAtrMultiple: Double = 0.0,
        /** When > 0, target = entry + ATR × this, overriding [targetPct]. */
        val targetAtrMultiple: Double = 0.0,
        val riskPerTrade: Double = 200.0,
        /** When > 0, overrides [riskPerTrade]: dollar risk = current capital × this / 100. */
        val riskPerTradePct: Double = 0.0,
        val maxOpenPositions: Int = 1,
        /** Optional buying-power ceiling: cap a position's notional at capital × this. 0 = uncapped
         *  (pure risk sizing). A cash/1× cap clamps tight-stop, high-priced names to a few shares
         *  regardless of risk, so it's opt-in, not a silent default. */
        val maxLeverage: Double = 0.0,
    )

    companion object {
        /**
         * Returns why [p] is not runnable, or null when it is. A zero/negative period silently
         * yields 0 trades (NaN-free but meaningless), so every caller — API controller, sweep
         * runner — must reject up front; browser form constraints protect nobody else.
         */
        fun validationError(p: Params): String? =
            when {
                p.rsiPeriod < 1 -> "rsiPeriod must be >= 1"
                p.smaFastPeriod < 1 -> "smaFastPeriod must be >= 1"
                p.smaSlowPeriod < 1 -> "smaSlowPeriod must be >= 1"
                p.maxOpenPositions < 1 -> "maxOpenPositions must be >= 1"
                p.rsiOversold <= 0.0 || p.rsiOversold > 100.0 -> "rsiOversold must be in (0, 100]"
                p.supportProximityPct < 0.0 -> "supportProximityPct must be >= 0"
                p.stopLossPct <= 0.0 -> "stopLossPct must be > 0"
                p.targetPct <= 0.0 -> "targetPct must be > 0"
                p.atrPeriod < 1 -> "atrPeriod must be >= 1"
                p.stopAtrMultiple < 0.0 -> "stopAtrMultiple must be >= 0 (0 = use stopLossPct)"
                p.targetAtrMultiple < 0.0 -> "targetAtrMultiple must be >= 0 (0 = use targetPct)"
                p.riskPerTradePct < 0.0 || p.riskPerTradePct > 100.0 -> "riskPerTradePct must be in [0, 100]"
                p.riskPerTradePct == 0.0 && p.riskPerTrade <= 0.0 -> "riskPerTrade must be > 0 when riskPerTradePct is unset"
                p.maxLeverage < 0.0 -> "maxLeverage must be >= 0 (0 = uncapped)"
                else -> null
            }
    }

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
    private val recentBars = mutableMapOf<Symbol, ArrayDeque<FiveMinuteBar>>() // ATR window (atrPeriod+1)
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
            recentBars[symbol] = ArrayDeque(warmupBars[symbol]?.takeLast(p.atrPeriod + 1) ?: emptyList())
        }
    }

    override fun onBar(
        symbol: Symbol,
        bar: FiveMinuteBar,
        account: BacktestAccountView,
    ): List<BacktestSignal> {
        val i = ind.getOrPut(symbol) { Indicators(p.rsiPeriod) }
        i.update(bar.close)
        val window = recentBars.getOrPut(symbol) { ArrayDeque() }
        window.addLast(bar)
        while (window.size > p.atrPeriod + 1) window.removeFirst()

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
        // ATR-scaled exits when requested: same multiple = wider stops in volatile regimes,
        // tighter in calm ones. A signal before the ATR window fills is skipped rather than
        // silently falling back to percent — mixing exit styles inside one run would poison sweeps.
        val needAtr = p.stopAtrMultiple > 0.0 || p.targetAtrMultiple > 0.0
        val atr = if (needAtr) AtrCalculator.atr(window.toList(), p.atrPeriod) else Double.NaN
        if (needAtr && atr.isNaN()) return emptyList()
        val stop = if (p.stopAtrMultiple > 0.0) entry - atr * p.stopAtrMultiple else entry * (1.0 - p.stopLossPct / 100.0)
        val target = if (p.targetAtrMultiple > 0.0) entry + atr * p.targetAtrMultiple else entry * (1.0 + p.targetPct / 100.0)
        val perShareRisk = entry - stop
        if (perShareRisk <= 0.0) return emptyList()
        // Size straight off the risk budget: % of current equity when set, else the fixed dollar
        // risk. Ruin guard: never risk more than the account holds, so size shrinks as equity falls
        // and a drained account (<= 0) opens nothing — no bounce-back from thin air.
        val rawRiskDollars = if (p.riskPerTradePct > 0.0) account.capital.toDouble() * p.riskPerTradePct / 100.0 else p.riskPerTrade
        val riskDollars = rawRiskDollars.coerceAtMost(account.capital.toDouble()).coerceAtLeast(0.0)
        var shares = floor(riskDollars / perShareRisk).toInt()
        // Optional buying-power ceiling: cap notional at capital × maxLeverage. 0 (default) = uncapped
        // (pure risk sizing) — a cash/1× cap silently masks the risk lever on tight-stop, high-priced
        // names (the old mandatory capital/maxOpenPositions cap did exactly that).
        if (p.maxLeverage > 0.0 && entry > 0.0) {
            shares = minOf(shares, floor(account.capital.toDouble() * p.maxLeverage / entry).toInt())
        }
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

        fun sma(period: Int): Double? {
            if (period <= 0 || closes.size < period) return null
            // Indexed sum instead of toList().takeLast(): called twice per bar, the list copies
            // are pure garbage on long backtests.
            var sum = 0.0
            for (i in closes.size - period until closes.size) sum += closes[i]
            return sum / period
        }
    }
}
