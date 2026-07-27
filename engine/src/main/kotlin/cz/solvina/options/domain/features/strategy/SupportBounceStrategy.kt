package cz.solvina.options.domain.features.strategy

import cz.solvina.options.domain.features.bars.AtrCalculator
import cz.solvina.options.domain.features.bars.Candle
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor

/**
 * Long-on-support-bounce: (optional) uptrend filter, price near/above the fast SMA acting as
 * support, RSI oversold and (optionally) turning up.
 *
 * Formerly `RuleBacktestStrategy` — same rules, same arithmetic, now expressed against the
 * host-neutral [StockStrategy] seam so the live host can run it unchanged.
 *
 * The instance registered in [StrategyRegistry] is the library template; runs use the copies
 * produced by [withParams].
 */
class SupportBounceStrategy(
    private val p: Params = Params(),
    private val timeframe: Timeframe = Timeframe.DAILY,
) : StockStrategy {
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

    override val id = ID

    override val displayName = "Support bounce (SMA + RSI)"

    override val params =
        listOf(
            ParamDescriptor("rsiPeriod", ParamType.INT, 14, min = 1.0, group = "Entry", help = "Wilder RSI lookback."),
            ParamDescriptor("rsiOversold", ParamType.DOUBLE, 40.0, 0.0, 100.0, "Entry", "Enter only below this RSI."),
            ParamDescriptor("requireRsiRising", ParamType.BOOLEAN, true, group = "Entry", help = "RSI must be turning up."),
            ParamDescriptor("smaFastPeriod", ParamType.INT, 50, min = 1.0, group = "Entry", help = "Fast SMA = support level."),
            ParamDescriptor("smaSlowPeriod", ParamType.INT, 200, min = 1.0, group = "Entry", help = "Slow SMA = trend filter."),
            ParamDescriptor("requireUptrend", ParamType.BOOLEAN, true, group = "Entry", help = "Close must be above the slow SMA."),
            ParamDescriptor("supportProximityPct", ParamType.DOUBLE, 3.0, 0.0, group = "Entry", help = "Max % above the fast SMA."),
            ParamDescriptor("stopLossPct", ParamType.DOUBLE, 3.0, 0.0, group = "Exit", help = "Stop distance when no ATR stop."),
            ParamDescriptor("targetPct", ParamType.DOUBLE, 6.0, 0.0, group = "Exit", help = "Target distance when no ATR target."),
            ParamDescriptor("atrPeriod", ParamType.INT, 14, min = 1.0, group = "Exit", help = "Wilder ATR lookback."),
            ParamDescriptor("stopAtrMultiple", ParamType.DOUBLE, 0.0, 0.0, group = "Exit", help = "> 0 overrides stopLossPct."),
            ParamDescriptor("targetAtrMultiple", ParamType.DOUBLE, 0.0, 0.0, group = "Exit", help = "> 0 overrides targetPct."),
            ParamDescriptor("riskPerTrade", ParamType.DOUBLE, 200.0, 0.0, group = "Money", help = "Fixed dollar risk per trade."),
            ParamDescriptor("riskPerTradePct", ParamType.DOUBLE, 0.0, 0.0, 100.0, "Money", "% of equity risked; overrides riskPerTrade."),
            ParamDescriptor("maxOpenPositions", ParamType.INT, 1, min = 1.0, group = "Money", help = "Concurrent position cap."),
            ParamDescriptor(
                "maxLeverage",
                ParamType.DOUBLE,
                0.0,
                0.0,
                group = "Money",
                help = "Notional cap = equity × this. 0 = uncapped.",
            ),
        )

    override val inputs =
        StrategyInputs(
            timeframes = listOf(timeframe),
            // The slow SMA is the binding constraint; a host must not have to guess that.
            warmupBars = maxOf(p.smaSlowPeriod, p.smaFastPeriod, p.rsiPeriod + 1, p.atrPeriod + 1),
        )

    override fun validate(params: StrategyParams): String? = validationError(from(params))

    override fun withParams(
        params: StrategyParams,
        timeframe: Timeframe,
    ): StockStrategy = SupportBounceStrategy(from(params), timeframe)

    private val ind = mutableMapOf<Symbol, RollingIndicators>()
    private val recentBars = mutableMapOf<Symbol, ArrayDeque<Candle>>() // ATR window (atrPeriod+1)

    override fun warmup(
        symbol: Symbol,
        history: Map<Timeframe, List<Candle>>,
    ) {
        val bars = history[inputs.primary].orEmpty()
        val i = RollingIndicators(p.rsiPeriod)
        bars.forEach { i.update(it.close) }
        ind[symbol] = i
        recentBars[symbol] = ArrayDeque(bars.takeLast(p.atrPeriod + 1))
    }

    override fun decide(ctx: StrategyContext): Decision? {
        val symbol = ctx.symbol
        val bar = ctx.candle
        // State advances on EVERY bar, before any early return: an indicator that only updated on
        // tradeable bars would drift apart between a capped and an uncapped run of the same data.
        val i = ind.getOrPut(symbol) { RollingIndicators(p.rsiPeriod) }
        i.update(bar.close)
        val window = recentBars.getOrPut(symbol) { ArrayDeque() }
        window.addLast(bar)
        while (window.size > p.atrPeriod + 1) window.removeFirst()

        if (ctx.exposureCount >= p.maxOpenPositions) return null

        val rsi = i.rsi ?: return null
        val smaFast = i.sma(p.smaFastPeriod) ?: return null
        val smaSlow = i.sma(p.smaSlowPeriod) ?: return null
        val close = bar.close

        val uptrendOk = !p.requireUptrend || close > smaSlow
        val nearSupport = close >= smaFast && close <= smaFast * (1.0 + p.supportProximityPct / 100.0)
        val rsiOk = rsi < p.rsiOversold
        val risingOk = !p.requireRsiRising || (i.prevRsi != null && rsi > i.prevRsi!!)
        if (!(uptrendOk && nearSupport && rsiOk && risingOk)) return null

        val entry = close
        // ATR-scaled exits when requested: same multiple = wider stops in volatile regimes,
        // tighter in calm ones. A signal before the ATR window fills is skipped rather than
        // silently falling back to percent — mixing exit styles inside one run would poison sweeps.
        val needAtr = p.stopAtrMultiple > 0.0 || p.targetAtrMultiple > 0.0
        val atr = if (needAtr) AtrCalculator.atr(window.toList(), p.atrPeriod) else Double.NaN
        if (needAtr && atr.isNaN()) return null
        val stop = if (p.stopAtrMultiple > 0.0) entry - atr * p.stopAtrMultiple else entry * (1.0 - p.stopLossPct / 100.0)
        val target = if (p.targetAtrMultiple > 0.0) entry + atr * p.targetAtrMultiple else entry * (1.0 + p.targetPct / 100.0)
        val perShareRisk = entry - stop
        if (perShareRisk <= 0.0) return null
        // Size straight off the risk budget: % of current equity when set, else the fixed dollar
        // risk. Ruin guard: never risk more than the account holds, so size shrinks as equity falls
        // and a drained account (<= 0) opens nothing — no bounce-back from thin air.
        val rawRiskDollars = if (p.riskPerTradePct > 0.0) ctx.equity.toDouble() * p.riskPerTradePct / 100.0 else p.riskPerTrade
        val riskDollars = rawRiskDollars.coerceAtMost(ctx.equity.toDouble()).coerceAtLeast(0.0)
        var shares = floor(riskDollars / perShareRisk).toInt()
        // Optional buying-power ceiling: cap notional at capital × maxLeverage. 0 (default) = uncapped
        // (pure risk sizing) — a cash/1× cap silently masks the risk lever on tight-stop, high-priced
        // names (the old mandatory capital/maxOpenPositions cap did exactly that).
        if (p.maxLeverage > 0.0 && entry > 0.0) {
            shares = minOf(shares, floor(ctx.equity.toDouble() * p.maxLeverage / entry).toInt())
        }
        if (shares <= 0) return null

        return Decision(
            entryPrice = BigDecimal(entry).setScale(2, RoundingMode.HALF_UP),
            stopLossPrice = BigDecimal(stop).setScale(2, RoundingMode.HALF_UP),
            profitTargetPrice = BigDecimal(target).setScale(2, RoundingMode.HALF_UP),
            shares = shares,
        )
    }

    companion object {
        const val ID = "support_bounce"

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

        /** Builds typed [Params] from a resolved param blob — the Phase-2 registry entry point. */
        fun from(sp: StrategyParams) =
            Params(
                rsiPeriod = sp.int("rsiPeriod"),
                rsiOversold = sp.double("rsiOversold"),
                requireRsiRising = sp.boolean("requireRsiRising"),
                smaFastPeriod = sp.int("smaFastPeriod"),
                smaSlowPeriod = sp.int("smaSlowPeriod"),
                requireUptrend = sp.boolean("requireUptrend"),
                supportProximityPct = sp.double("supportProximityPct"),
                stopLossPct = sp.double("stopLossPct"),
                targetPct = sp.double("targetPct"),
                atrPeriod = sp.int("atrPeriod"),
                stopAtrMultiple = sp.double("stopAtrMultiple"),
                targetAtrMultiple = sp.double("targetAtrMultiple"),
                riskPerTrade = sp.double("riskPerTrade"),
                riskPerTradePct = sp.double("riskPerTradePct"),
                maxOpenPositions = sp.int("maxOpenPositions"),
                maxLeverage = sp.double("maxLeverage"),
            )
    }
}
