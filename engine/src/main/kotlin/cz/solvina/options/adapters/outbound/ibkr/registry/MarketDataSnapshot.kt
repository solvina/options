package cz.solvina.options.adapters.outbound.ibkr.registry

import java.time.Instant

data class MarketDataSnapshot(
    val bid: Double = Double.NaN,
    val ask: Double = Double.NaN,
    val last: Double = Double.NaN,
    val close: Double = Double.NaN,
    val delta: Double = Double.NaN,
    val impliedVol: Double = Double.NaN,
    val gamma: Double = Double.NaN,
    val vega: Double = Double.NaN,
    val theta: Double = Double.NaN,
    val asOf: Instant = Instant.now(),
)
