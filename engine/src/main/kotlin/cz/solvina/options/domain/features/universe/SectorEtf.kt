package cz.solvina.options.domain.features.universe

import cz.solvina.options.domain.models.Symbol

/**
 * GICS sector → SPDR sector ETF, used as the backtest benchmark for a symbol's sector.
 * Keys match the `sector` values stored in instrument_universe. Symbols in the synthetic
 * "Index" sector are themselves benchmarks and map to nothing.
 */
object SectorEtf {
    val BROAD_MARKET = Symbol("SPY")

    private val bySector =
        mapOf(
            "information technology" to "XLK",
            "financials" to "XLF",
            "energy" to "XLE",
            "health care" to "XLV",
            "consumer discretionary" to "XLY",
            "consumer staples" to "XLP",
            "industrials" to "XLI",
            "materials" to "XLB",
            "utilities" to "XLU",
            "real estate" to "XLRE",
            "communication services" to "XLC",
        )

    fun forSector(sector: String?): Symbol? =
        sector
            ?.trim()
            ?.lowercase()
            ?.let { bySector[it] }
            ?.let { Symbol(it) }
}
