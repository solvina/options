package cz.solvina.options.domain.features.strategy

import cz.solvina.options.domain.features.bars.AtrCalculator
import cz.solvina.options.domain.features.bars.Candle
import cz.solvina.options.domain.features.bars.MoneyFlow
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol

/**
 * Long mean reversion on a money-flow washout: enter when MFI drops below [Params.mfiThreshold],
 * exit on an ATR-scaled bracket.
 *
 * ### Why the exits are ATR-based and not "the last bar's high"
 * The rule as it circulates is "buy the close, target the last day's high". Measured over 3,780
 * daily signals across 198 symbols (1990-2026, path-simulated with stops winning same-bar ties and
 * a 0.10% round-trip cost), that version wins **75%** of the time and still nets roughly nothing:
 * +0.011R expectancy, because each win is a fraction of an R (median +0.15R) while each loss is a
 * full one. Letting the trade run on an R frame inverts the profile — 3.0/1.5 ATR wins only 44% of
 * the time but returns +0.174R. Hence the defaults here. [Params.targetLastHigh] keeps the original
 * rule available as a comparison arm so a sweep can show the difference rather than assert it.
 *
 * ### Why the entry is a limit, and why the tolerance defaults to zero
 * Live, the signal is computed after the close and the order rests for the next session, so this
 * emits a limit rather than a close-price fill. Entering at the next open instead of the signal
 * close costs about 8% of the edge — a haircut worth paying to keep the backtest and the live host
 * filling identically.
 *
 * [Params.limitToleranceAtr] defaults to **0.0** despite an idealised probe favouring 1.0, because
 * of where the bracket is anchored. A [Decision] carries absolute stop and target *prices*, fixed
 * at signal time off [Decision.entryPrice] — they do not follow the fill. Widen the limit and the
 * fill routinely lands below it (a gap-down fills at the open), so the stop ends up further from
 * the fill than intended and the target nearer, silently degrading the bracket. It is measurable:
 * across 25 symbols 2015-2026, avgLossR drifts -0.90 -> -0.47 and the win rate 25% -> 15% as
 * tolerance goes 0.0 -> 1.0, purely from that anchoring. The probe assumed a bracket measured from
 * the fill; until the bracket is derived from the fill (the [Decision] doc's `PositionSizer` seam)
 * a tolerance above zero is a loss, not a gain.
 */
class MfiOversoldStrategy(
    private val p: Params = Params(),
    private val timeframe: Timeframe = Timeframe.DAILY,
) : StockStrategy {
    data class Params(
        val mfiPeriod: Int = 14,
        val mfiThreshold: Double = 10.0,
        /**
         * Limit offset above the signal close, in ATR. 0 (the default, and the only value that
         * currently helps) rests the limit exactly at the close — see the class doc on anchoring.
         */
        val limitToleranceAtr: Double = 0.0,
        val atrPeriod: Int = 14,
        val stopAtrMultiple: Double = 1.5,
        val targetAtrMultiple: Double = 3.0,
        /** The circulating rule: target the signal bar's high instead of an ATR distance. */
        val targetLastHigh: Boolean = false,
        /** Used only when the matching ATR multiple is 0. */
        val stopLossPct: Double = 3.0,
        val targetPct: Double = 6.0,
        val riskPerTrade: Double = 200.0,
        val riskPerTradePct: Double = 0.0,
        val maxOpenPositions: Int = 3,
        val maxLeverage: Double = 0.0,
    )

    override val id = ID

    override val displayName = "MFI oversold (mean reversion)"

    override val params =
        listOf(
            ParamDescriptor("mfiPeriod", ParamType.INT, 14, min = 1.0, group = "Entry", help = "Money Flow Index lookback."),
            ParamDescriptor("mfiThreshold", ParamType.DOUBLE, 10.0, 0.0, 100.0, "Entry", "Enter only below this MFI."),
            ParamDescriptor(
                "limitToleranceAtr",
                ParamType.DOUBLE,
                0.0,
                0.0,
                group = "Entry",
                help = "Entry limit = close + this x ATR. Fills more often, but the bracket is anchored to the limit, not the fill.",
            ),
            ParamDescriptor("atrPeriod", ParamType.INT, 14, min = 1.0, group = "Exit", help = "Wilder ATR lookback."),
            ParamDescriptor("stopAtrMultiple", ParamType.DOUBLE, 1.5, 0.0, group = "Exit", help = "> 0 overrides stopLossPct."),
            ParamDescriptor("targetAtrMultiple", ParamType.DOUBLE, 3.0, 0.0, group = "Exit", help = "> 0 overrides targetPct."),
            ParamDescriptor(
                "targetLastHigh",
                ParamType.BOOLEAN,
                false,
                group = "Exit",
                help = "Target the signal bar's high instead of an ATR distance (the original rule).",
            ),
            ParamDescriptor("stopLossPct", ParamType.DOUBLE, 3.0, 0.0, group = "Exit", help = "Stop distance when no ATR stop."),
            ParamDescriptor("targetPct", ParamType.DOUBLE, 6.0, 0.0, group = "Exit", help = "Target distance when no ATR target."),
        ) + EntrySizer.moneyDescriptors()

    override val inputs =
        StrategyInputs(
            timeframes = listOf(timeframe),
            // MFI needs one extra bar to classify the first flow, ATR one extra for the first true
            // range. The limit offset reads the same ATR, so nothing widens this further.
            warmupBars = maxOf(p.mfiPeriod + 1, p.atrPeriod + 1),
        )

    override val entryMode = StrategyEntryMode.LIMIT

    override fun validate(params: StrategyParams): String? = validationError(from(params))

    override fun withParams(
        params: StrategyParams,
        timeframe: Timeframe,
    ): StockStrategy = MfiOversoldStrategy(from(params), timeframe)

    private val flow = mutableMapOf<Symbol, MoneyFlow>()
    private val recentBars = mutableMapOf<Symbol, ArrayDeque<Candle>>() // ATR window (atrPeriod + 1)

    override fun warmup(
        symbol: Symbol,
        history: Map<Timeframe, List<Candle>>,
    ) {
        val bars = history[inputs.primary].orEmpty()
        val f = MoneyFlow(p.mfiPeriod)
        bars.forEach { f.update(it.high, it.low, it.close, it.volume.toDouble()) }
        flow[symbol] = f
        recentBars[symbol] = ArrayDeque(bars.takeLast(p.atrPeriod + 1))
    }

    override fun decide(ctx: StrategyContext): Decision? {
        val symbol = ctx.symbol
        val bar = ctx.candle
        // State advances on EVERY bar, before any early return: an indicator that only updated on
        // tradeable bars would drift apart between a capped and an uncapped run of the same data.
        val f = flow.getOrPut(symbol) { MoneyFlow(p.mfiPeriod) }
        f.update(bar.high, bar.low, bar.close, bar.volume.toDouble())
        val window = recentBars.getOrPut(symbol) { ArrayDeque() }
        window.addLast(bar)
        while (window.size > p.atrPeriod + 1) window.removeFirst()

        if (ctx.exposureCount >= p.maxOpenPositions) return null

        val mfi = f.value ?: return null
        if (mfi >= p.mfiThreshold) return null

        // ATR is needed for the limit offset even when both exit multiples are 0, so it is fetched
        // unconditionally rather than behind Frame.needsAtr.
        val atr = AtrCalculator.atr(window.toList(), p.atrPeriod)
        if (atr.isNaN()) return null

        val entry = bar.close + p.limitToleranceAtr * atr
        return EntrySizer.size(
            entry = entry,
            atr = atr,
            equity = ctx.equity.toDouble(),
            f =
                EntrySizer.Frame(
                    stopLossPct = p.stopLossPct,
                    targetPct = p.targetPct,
                    stopAtrMultiple = p.stopAtrMultiple,
                    targetAtrMultiple = p.targetAtrMultiple,
                    riskPerTrade = p.riskPerTrade,
                    riskPerTradePct = p.riskPerTradePct,
                    maxLeverage = p.maxLeverage,
                    // The original rule targets the signal bar's high. With a positive limit
                    // tolerance the entry can sit above that high, in which case EntrySizer
                    // rejects the trade rather than booking a target behind the entry.
                    targetOverride = if (p.targetLastHigh) bar.high else null,
                ),
        )
    }

    companion object {
        const val ID = "mfi_oversold"

        fun validationError(p: Params): String? =
            when {
                p.mfiPeriod < 1 -> "mfiPeriod must be >= 1"
                p.mfiThreshold <= 0.0 || p.mfiThreshold > 100.0 -> "mfiThreshold must be in (0, 100]"
                p.limitToleranceAtr < 0.0 -> "limitToleranceAtr must be >= 0"
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

        fun from(sp: StrategyParams) =
            Params(
                mfiPeriod = sp.int("mfiPeriod"),
                mfiThreshold = sp.double("mfiThreshold"),
                limitToleranceAtr = sp.double("limitToleranceAtr"),
                atrPeriod = sp.int("atrPeriod"),
                stopAtrMultiple = sp.double("stopAtrMultiple"),
                targetAtrMultiple = sp.double("targetAtrMultiple"),
                targetLastHigh = sp.boolean("targetLastHigh"),
                stopLossPct = sp.double("stopLossPct"),
                targetPct = sp.double("targetPct"),
                riskPerTrade = sp.double("riskPerTrade"),
                riskPerTradePct = sp.double("riskPerTradePct"),
                maxOpenPositions = sp.int("maxOpenPositions"),
                maxLeverage = sp.double("maxLeverage"),
            )
    }
}
