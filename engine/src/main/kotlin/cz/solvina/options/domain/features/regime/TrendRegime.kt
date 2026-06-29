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
)
