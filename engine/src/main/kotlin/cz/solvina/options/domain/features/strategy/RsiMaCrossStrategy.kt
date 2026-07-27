package cz.solvina.options.domain.features.strategy

import cz.solvina.options.domain.features.bars.AtrCalculator
import cz.solvina.options.domain.features.bars.Candle
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol

/**
 * Long when RSI crosses **above its own moving average** — momentum turning up, measured against
 * momentum's own recent level rather than a fixed line.
 *
 * The entry is a genuine crossing (`prev <= MA(prev) && now > MA(now)`), not a level test. A level
 * test fires on every bar of an uptrend and turns one idea into a hundred correlated entries; a
 * cross fires once, on the turn. The oversold gate and the trend filter are independent switches
 * because whether this cross wants them is a backtest question, not a design decision.
 */
class RsiMaCrossStrategy(
    private val p: Params = Params(),
    private val timeframe: Timeframe = Timeframe.DAILY,
) : StockStrategy {
    data class Params(
        val rsiPeriod: Int = 14,
        /** SMA length applied to the RSI series itself — the line RSI must cross. */
        val rsiMaPeriod: Int = 14,
        /** When true, only take crosses that happen below [rsiOversold] (a turn from a dip). */
        val requireOversold: Boolean = false,
        val rsiOversold: Double = 40.0,
        /** When true, price must be above [smaTrendPeriod] — crosses only in an established uptrend. */
        val requireUptrend: Boolean = false,
        val smaTrendPeriod: Int = 200,
        val stopLossPct: Double = 3.0,
        val targetPct: Double = 6.0,
        val atrPeriod: Int = 14,
        val stopAtrMultiple: Double = 0.0,
        val targetAtrMultiple: Double = 0.0,
        val riskPerTrade: Double = 200.0,
        val riskPerTradePct: Double = 0.0,
        val maxOpenPositions: Int = 1,
        val maxLeverage: Double = 0.0,
    )

    override val id = ID

    override val displayName = "RSI / MA cross"

    override val params =
        listOf(
            ParamDescriptor("rsiPeriod", ParamType.INT, 14, min = 1.0, group = "Entry", help = "Wilder RSI lookback."),
            ParamDescriptor("rsiMaPeriod", ParamType.INT, 14, min = 1.0, group = "Entry", help = "SMA length applied to the RSI itself."),
            ParamDescriptor("requireOversold", ParamType.BOOLEAN, false, group = "Entry", help = "Only cross below the oversold level."),
            ParamDescriptor("rsiOversold", ParamType.DOUBLE, 40.0, 0.0, 100.0, "Entry", "The oversold level, when gated."),
            ParamDescriptor("requireUptrend", ParamType.BOOLEAN, false, group = "Entry", help = "Only cross above the trend SMA."),
            ParamDescriptor("smaTrendPeriod", ParamType.INT, 200, min = 1.0, group = "Entry", help = "Trend SMA, when gated."),
            ParamDescriptor("stopLossPct", ParamType.DOUBLE, 3.0, 0.0, group = "Exit", help = "Stop distance when no ATR stop."),
            ParamDescriptor("targetPct", ParamType.DOUBLE, 6.0, 0.0, group = "Exit", help = "Target distance when no ATR target."),
            ParamDescriptor("atrPeriod", ParamType.INT, 14, min = 1.0, group = "Exit", help = "Wilder ATR lookback."),
            ParamDescriptor("stopAtrMultiple", ParamType.DOUBLE, 0.0, 0.0, group = "Exit", help = "> 0 overrides stopLossPct."),
            ParamDescriptor("targetAtrMultiple", ParamType.DOUBLE, 0.0, 0.0, group = "Exit", help = "> 0 overrides targetPct."),
        ) + EntrySizer.moneyDescriptors()

    override val inputs =
        StrategyInputs(
            timeframes = listOf(timeframe),
            // The RSI MA only starts rsiMaPeriod bars after RSI itself does, so the two stack; the
            // trend SMA is usually the binding constraint but must not be assumed to be.
            warmupBars = maxOf(p.smaTrendPeriod, p.rsiPeriod + p.rsiMaPeriod + 1, p.atrPeriod + 1),
        )

    override fun validate(params: StrategyParams): String? = validationError(from(params))

    override fun withParams(
        params: StrategyParams,
        timeframe: Timeframe,
    ): StockStrategy = RsiMaCrossStrategy(from(params), timeframe)

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
        val bar = ctx.candle
        // State advances on EVERY bar, before any early return: an indicator that only updated on
        // tradeable bars would drift apart between a capped and an uncapped run of the same data.
        val i = ind.getOrPut(ctx.symbol) { RollingIndicators(p.rsiPeriod) }
        i.update(bar.close)
        val window = recentBars.getOrPut(ctx.symbol) { ArrayDeque() }
        window.addLast(bar)
        while (window.size > p.atrPeriod + 1) window.removeFirst()

        if (ctx.exposureCount >= p.maxOpenPositions) return null

        val rsi = i.rsi ?: return null
        val prevRsi = i.prevRsi ?: return null
        val rsiMa = i.rsiSma(p.rsiMaPeriod) ?: return null
        // Both sides of the comparison move: the previous bar is tested against the MA *as it stood
        // then*, otherwise a rising MA alone could manufacture a cross that never happened.
        val prevRsiMa = i.prevRsiSma(p.rsiMaPeriod) ?: return null

        val crossedUp = prevRsi <= prevRsiMa && rsi > rsiMa
        if (!crossedUp) return null
        if (p.requireOversold && rsi >= p.rsiOversold) return null
        if (p.requireUptrend) {
            val trendSma = i.sma(p.smaTrendPeriod) ?: return null
            if (bar.close <= trendSma) return null
        }

        val frame = frame(p)
        val atr = if (frame.needsAtr) AtrCalculator.atr(window.toList(), p.atrPeriod) else Double.NaN
        return EntrySizer.size(entry = bar.close, atr = atr, equity = ctx.equity.toDouble(), f = frame)
    }

    companion object {
        const val ID = "rsi_ma_cross"

        /** Returns why [p] is not runnable, or null when it is. */
        fun validationError(p: Params): String? =
            when {
                p.rsiPeriod < 1 -> "rsiPeriod must be >= 1"
                p.rsiMaPeriod < 1 -> "rsiMaPeriod must be >= 1"
                p.smaTrendPeriod < 1 -> "smaTrendPeriod must be >= 1"
                p.rsiOversold <= 0.0 || p.rsiOversold > 100.0 -> "rsiOversold must be in (0, 100]"
                else ->
                    EntrySizer.validationError(
                        stopLossPct = p.stopLossPct,
                        targetPct = p.targetPct,
                        atrPeriod = p.atrPeriod,
                        stopAtrMultiple = p.stopAtrMultiple,
                        targetAtrMultiple = p.targetAtrMultiple,
                        riskPerTrade = p.riskPerTrade,
                        riskPerTradePct = p.riskPerTradePct,
                        maxOpenPositions = p.maxOpenPositions,
                        maxLeverage = p.maxLeverage,
                    )
            }

        private fun frame(p: Params) =
            EntrySizer.Frame(
                stopLossPct = p.stopLossPct,
                targetPct = p.targetPct,
                stopAtrMultiple = p.stopAtrMultiple,
                targetAtrMultiple = p.targetAtrMultiple,
                riskPerTrade = p.riskPerTrade,
                riskPerTradePct = p.riskPerTradePct,
                maxLeverage = p.maxLeverage,
            )

        fun from(sp: StrategyParams) =
            Params(
                rsiPeriod = sp.int("rsiPeriod"),
                rsiMaPeriod = sp.int("rsiMaPeriod"),
                requireOversold = sp.boolean("requireOversold"),
                rsiOversold = sp.double("rsiOversold"),
                requireUptrend = sp.boolean("requireUptrend"),
                smaTrendPeriod = sp.int("smaTrendPeriod"),
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
