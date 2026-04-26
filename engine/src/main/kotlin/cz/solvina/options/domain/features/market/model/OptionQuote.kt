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
)
