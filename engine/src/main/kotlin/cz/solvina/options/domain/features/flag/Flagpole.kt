package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.bars.FiveMinuteBar

data class Flagpole(
    val startBar: FiveMinuteBar,
    val endBar: FiveMinuteBar,
    /** Vertical height of the pole = endBar.high − startBar.low */
    val height: Double,
    /** Average volume across the pole bars */
    val avgVolume: Double,
)
