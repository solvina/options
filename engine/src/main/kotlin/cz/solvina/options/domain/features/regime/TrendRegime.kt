package cz.solvina.options.domain.features.regime

import java.math.BigDecimal

/** A symbol's trend regime — the directional backdrop for a spread strategy (bull/bear/neutral). */
enum class TrendRegime { UPTREND, DOWNTREND, NEUTRAL }

/** A strategy's directional bias (bull put = bullish, bear call = bearish). */
enum class DirectionalBias { BULLISH, BEARISH, NEUTRAL }

/** How a [TrendRegime] relates to a strategy's [DirectionalBias] — for observe-only logging. */
fun alignment(
    regime: TrendRegime,
    bias: DirectionalBias,
): String =
    when {
        regime == TrendRegime.NEUTRAL || bias == DirectionalBias.NEUTRAL -> "neutral"
        (bias == DirectionalBias.BULLISH && regime == TrendRegime.UPTREND) ||
            (bias == DirectionalBias.BEARISH && regime == TrendRegime.DOWNTREND) -> "aligned"
        else -> "OPPOSITE"
    }

/** Regime plus the inputs that produced it, for logging / future UI. */
data class RegimeSignal(
    val regime: TrendRegime,
    val lastClose: BigDecimal?,
    val smaFast: BigDecimal?,
    val smaSlow: BigDecimal?,
    /** RSI(period) on daily closes; null until enough history. */
    val rsi: BigDecimal? = null,
    /** Which strategy the combined signal favours (bull put = BULLISH, bear call = BEARISH). */
    val bias: DirectionalBias = DirectionalBias.NEUTRAL,
)

/**
 * Combine trend ([regime]) and momentum ([rsi]) into a strategy bias. Trend-following and
 * mean-reverting cover each other's blind spots:
 *  - UPTREND & not overbought        → BULLISH (sell the downside we don't expect → bull put)
 *  - DOWNTREND & not oversold        → BEARISH (sell the upside we don't expect → bear call)
 *  - NEUTRAL & oversold              → BULLISH (fade the drop → bull put)
 *  - NEUTRAL & overbought            → BEARISH (fade the rally → bear call)
 *  - stretched-with-trend / mid-RSI  → NEUTRAL (don't force a side)
 *
 * [rsi] null (insufficient history) falls back to trend only. Overbought = rsi ≥ [overbought];
 * oversold = rsi ≤ [oversold].
 */
fun directionalBias(
    regime: TrendRegime,
    rsi: BigDecimal?,
    overbought: Double,
    oversold: Double,
): DirectionalBias {
    if (rsi == null) {
        return when (regime) {
            TrendRegime.UPTREND -> DirectionalBias.BULLISH
            TrendRegime.DOWNTREND -> DirectionalBias.BEARISH
            TrendRegime.NEUTRAL -> DirectionalBias.NEUTRAL
        }
    }
    val overboughtHit = rsi >= BigDecimal(overbought)
    val oversoldHit = rsi <= BigDecimal(oversold)
    return when {
        regime == TrendRegime.UPTREND && !overboughtHit -> DirectionalBias.BULLISH
        regime == TrendRegime.DOWNTREND && !oversoldHit -> DirectionalBias.BEARISH
        regime == TrendRegime.NEUTRAL && oversoldHit -> DirectionalBias.BULLISH
        regime == TrendRegime.NEUTRAL && overboughtHit -> DirectionalBias.BEARISH
        else -> DirectionalBias.NEUTRAL
    }
}
