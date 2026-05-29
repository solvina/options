package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.features.bars.LinearRegression

data class Flag(
    val bars: List<FiveMinuteBar>,
    /** Lowest low across all flag bars — used as the stop-loss anchor */
    val lowestLow: Double,
    /** Regression line fitted through candle highs */
    val upperResistance: LinearRegression.RegressionLine,
    /** Regression line fitted through candle lows (for channel confirmation) */
    val lowerSupport: LinearRegression.RegressionLine,
    /** How far price has retraced from the pole top, as a fraction (0 = none, 1 = full) */
    val retracement: Double,
)
