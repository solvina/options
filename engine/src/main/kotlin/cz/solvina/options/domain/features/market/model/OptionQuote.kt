package cz.solvina.options.domain.features.market.model

import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionGreeks

data class OptionQuote(
    val contract: OptionContract,
    val bid: Money,
    val ask: Money,
    val mid: Money,
    val greeks: OptionGreeks,
    /**
     * True when this quote's prices/greeks came from a Black-Scholes fallback (no live market data),
     * not from a real IBKR snapshot. Synthetic quotes are fine for theoretical strike selection but
     * MUST NOT be used to launch an order — there is no real market, so the entry would never fill.
     */
    val synthetic: Boolean = false,
)
