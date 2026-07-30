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
    // Underlying price IBKR used to compute the greeks above, delivered free on every option
    // computation tick (tickOptionComputation's last argument). Lets a held option-leg stream
    // report its underlying's spot without a second market-data line. NaN on non-option requests.
    val underlyingPrice: Double = Double.NaN,
    // Separate from [asOf] on purpose: bid/ask ticks refresh asOf without carrying undPrice, so a
    // shared timestamp would make a stale underlying look fresh whenever quotes keep ticking.
    val underlyingPriceAsOf: Instant? = null,
    val asOf: Instant = Instant.now(),
)
