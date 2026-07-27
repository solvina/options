package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.bars.Candle

data class Flagpole(
    val startBar: Candle,
    val endBar: Candle,
    /** Vertical height of the pole = endBar.high − startBar.low */
    val height: Double,
    /** Average volume across the pole bars */
    val avgVolume: Double,
    /** Number of 5-minute bars that formed the pole */
    val barCount: Int,
)
