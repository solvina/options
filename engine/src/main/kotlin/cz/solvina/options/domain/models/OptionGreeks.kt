package cz.solvina.options.domain.models

data class OptionGreeks(
    val delta: Double,
    val gamma: Double,
    val theta: Double,
    val vega: Double,
    val iv: Double,
)
