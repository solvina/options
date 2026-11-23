package cz.solvina.options.adapters.outbound.ibkr.market

import cz.solvina.options.domain.features.market.BlackScholes
import cz.solvina.options.domain.features.market.GreeksEstimator
import cz.solvina.options.domain.models.OptionType
import java.math.BigDecimal
import kotlin.math.abs

/**
 * Black-Scholes AIM for the spread short strike (bull put and bear call). It only decides which strikes to ask IBKR for;
 * the short strike is still chosen on IBKR's live deltas, and every price stays a market quote.
 */
internal object PutStrikeAim {
    // A live delta this close to target counts as a hit even if the aimed strikes don't straddle it.
    const val HIT_TOLERANCE = 0.03

    /** The strike whose put |delta| equals [targetDelta] (bisection; |delta| rises with strike). */
    fun strikeForDelta(
        spot: Double,
        t: Double,
        sigma: Double,
        targetDelta: Double,
    ): Double {
        var lo = spot * 0.2
        var hi = spot
        repeat(60) {
            val mid = (lo + hi) / 2
            val d = abs(BlackScholes.putDelta(spot, mid, t, GreeksEstimator.RISK_FREE_RATE, sigma))
            if (d < targetDelta) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /**
     * The strike whose |delta| equals [targetDelta] for [type]. Call |delta| FALLS as the strike
     * rises (search above spot); put |delta| rises with the strike (search below spot).
     */
    fun strikeForDelta(
        type: OptionType,
        spot: Double,
        t: Double,
        sigma: Double,
        targetDelta: Double,
    ): Double {
        if (type == OptionType.PUT) return strikeForDelta(spot, t, sigma, targetDelta)
        var lo = spot
        var hi = spot * 5
        repeat(60) {
            val mid = (lo + hi) / 2
            val d = abs(BlackScholes.delta(OptionType.CALL, spot, mid, t, GreeksEstimator.RISK_FREE_RATE, sigma))
            if (d > targetDelta) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /** The [count] listed strikes nearest the aim. */
    fun nearest(
        strikes: List<BigDecimal>,
        aim: Double,
        count: Int,
    ): List<BigDecimal> = strikes.distinct().sortedBy { abs(it.toDouble() - aim) }.take(count)

    /** True when the live |deltas| straddle the target, or one sits within [HIT_TOLERANCE] of it. */
    fun covers(
        liveAbsDeltas: List<Double>,
        targetDelta: Double,
    ): Boolean {
        if (liveAbsDeltas.isEmpty()) return false
        if (liveAbsDeltas.any { abs(it - targetDelta) <= HIT_TOLERANCE }) return true
        return liveAbsDeltas.min() <= targetDelta && liveAbsDeltas.max() >= targetDelta
    }
}
