package cz.solvina.options.adapters.outbound.ibkr

/**
 * Resolved IBKR routing for one symbol, as consumed by [IbkrContractFactory].
 *
 * No longer bound from yaml (`ibkr.instruments` is gone): the factory maps
 * `UniversePort.routingFor(symbol)` — the DB's `instrument_universe` routing columns — onto this
 * value type, so the defaults below only matter for symbols with no routing rows.
 */
data class InstrumentDef(
    val currency: String = "USD",
    val exchange: String = "SMART",
    val optionExchange: String = "SMART",
    val multiplier: String = "100",
    val marketExchange: String = "US",
)
