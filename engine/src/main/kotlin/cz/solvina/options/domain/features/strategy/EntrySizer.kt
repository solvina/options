package cz.solvina.options.domain.features.strategy

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor

/**
 * Turns "I want in here" into a sized long [Decision]: stop and target placement plus share count.
 *
 * Shared by every long stock strategy so the money management cannot drift between them — the
 * risk-per-trade rules, the ruin guard and the leverage cap are one implementation, tested once.
 * (This is the [Decision] doc's `PositionSizer` in embryo; it still lives on the strategy side of
 * the seam because sizing needs equity, which the context already carries.)
 */
object EntrySizer {
    /**
     * Exit distances and money management. ATR multiples override their percentage counterparts,
     * so a run is either percent-based or volatility-scaled, never a mix.
     */
    data class Frame(
        val stopLossPct: Double,
        val targetPct: Double,
        val stopAtrMultiple: Double,
        val targetAtrMultiple: Double,
        val riskPerTrade: Double,
        val riskPerTradePct: Double,
        val maxLeverage: Double,
        /**
         * An absolute target level, overriding both [targetPct] and [targetAtrMultiple]. For rules
         * that name a *price* rather than a distance — "take profit at the signal bar's high". Null
         * (the default) leaves every existing caller on the distance-based path unchanged.
         */
        val targetOverride: Double? = null,
    ) {
        val needsAtr: Boolean get() = stopAtrMultiple > 0.0 || targetAtrMultiple > 0.0
    }

    /**
     * Returns the sized decision, or null when this entry cannot be taken: ATR requested but not
     * yet available, a non-positive risk per share, or a size that rounds to zero shares.
     *
     * [atr] may be NaN whenever [Frame.needsAtr] is false.
     */
    fun size(
        entry: Double,
        atr: Double,
        equity: Double,
        f: Frame,
    ): Decision? {
        // A signal before the ATR window fills is skipped rather than silently falling back to
        // percent — mixing exit styles inside one run would poison sweeps.
        if (f.needsAtr && atr.isNaN()) return null
        val stop = if (f.stopAtrMultiple > 0.0) entry - atr * f.stopAtrMultiple else entry * (1.0 - f.stopLossPct / 100.0)
        val target =
            f.targetOverride
                ?: if (f.targetAtrMultiple > 0.0) entry + atr * f.targetAtrMultiple else entry * (1.0 + f.targetPct / 100.0)
        val perShareRisk = entry - stop
        if (perShareRisk <= 0.0) return null
        // An override can legitimately land at or below the entry (a bar that closed on its high),
        // which is not a trade. Distance-based targets cannot, so this only bites the override path.
        if (target <= entry) return null
        // Size straight off the risk budget: % of current equity when set, else the fixed dollar
        // risk. Ruin guard: never risk more than the account holds, so size shrinks as equity falls
        // and a drained account (<= 0) opens nothing — no bounce-back from thin air.
        val rawRiskDollars = if (f.riskPerTradePct > 0.0) equity * f.riskPerTradePct / 100.0 else f.riskPerTrade
        val riskDollars = rawRiskDollars.coerceAtMost(equity).coerceAtLeast(0.0)
        var shares = floor(riskDollars / perShareRisk).toInt()
        // Optional buying-power ceiling: cap notional at capital × maxLeverage. 0 (default) = uncapped
        // (pure risk sizing) — a cash/1× cap silently masks the risk lever on high-priced, tight-stop
        // names.
        if (f.maxLeverage > 0.0 && entry > 0.0) {
            shares = minOf(shares, floor(equity * f.maxLeverage / entry).toInt())
        }
        if (shares <= 0) return null

        return Decision(
            entryPrice = BigDecimal(entry).setScale(2, RoundingMode.HALF_UP),
            stopLossPrice = BigDecimal(stop).setScale(2, RoundingMode.HALF_UP),
            profitTargetPrice = BigDecimal(target).setScale(2, RoundingMode.HALF_UP),
            shares = shares,
        )
    }

    /** The money-management descriptors every long strategy shares, so their forms stay identical. */
    fun moneyDescriptors(): List<ParamDescriptor> =
        listOf(
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

    /** Shared validation for [Frame]-shaped params; returns the first problem or null. */
    fun validationError(
        stopLossPct: Double,
        targetPct: Double,
        atrPeriod: Int,
        stopAtrMultiple: Double,
        targetAtrMultiple: Double,
        riskPerTrade: Double,
        riskPerTradePct: Double,
        maxOpenPositions: Int,
        maxLeverage: Double,
    ): String? =
        when {
            maxOpenPositions < 1 -> "maxOpenPositions must be >= 1"
            stopLossPct <= 0.0 -> "stopLossPct must be > 0"
            targetPct <= 0.0 -> "targetPct must be > 0"
            atrPeriod < 1 -> "atrPeriod must be >= 1"
            stopAtrMultiple < 0.0 -> "stopAtrMultiple must be >= 0 (0 = use stopLossPct)"
            targetAtrMultiple < 0.0 -> "targetAtrMultiple must be >= 0 (0 = use targetPct)"
            riskPerTradePct < 0.0 || riskPerTradePct > 100.0 -> "riskPerTradePct must be in [0, 100]"
            riskPerTradePct == 0.0 && riskPerTrade <= 0.0 -> "riskPerTrade must be > 0 when riskPerTradePct is unset"
            maxLeverage < 0.0 -> "maxLeverage must be >= 0 (0 = uncapped)"
            else -> null
        }
}
