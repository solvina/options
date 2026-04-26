package cz.solvina.options.adapters.outbound.ibkr.registry

data class MarketDataSnapshot(
    var bid: Double = Double.NaN,
    var ask: Double = Double.NaN,
    var last: Double = Double.NaN,
    var close: Double = Double.NaN,
    var delta: Double = Double.NaN,
    var impliedVol: Double = Double.NaN,
    var gamma: Double = Double.NaN,
    var vega: Double = Double.NaN,
    var theta: Double = Double.NaN,
)
