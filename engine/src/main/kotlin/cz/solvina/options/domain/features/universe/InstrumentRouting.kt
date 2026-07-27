package cz.solvina.options.domain.features.universe

/**
 * Broker routing for one symbol, with defaults already applied — how to build a stock/option
 * contract for it and which exchange session governs its trading hours.
 *
 * Sourced from the DB (`instrument_universe` routing columns); a NULL column falls back to the
 * US default below, so US symbols need no rows of their own.
 */
data class InstrumentRouting(
    val currency: String = "USD",
    val stockExchange: String = "SMART",
    val optionExchange: String = "SMART",
    val multiplier: String = "100",
    val marketExchange: String = "US",
)
