package cz.solvina.options.domain.features.regime

import java.math.BigDecimal

/** A symbol's trend regime — the directional backdrop for a spread strategy (bull/bear/neutral). */
enum class TrendRegime { UPTREND, DOWNTREND, NEUTRAL }

/** Regime plus the inputs that produced it, for logging / future UI. */
data class RegimeSignal(
    val regime: TrendRegime,
    val lastClose: BigDecimal?,
    val smaFast: BigDecimal?,
    val smaSlow: BigDecimal?,
)
