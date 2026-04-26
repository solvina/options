package cz.solvina.options.domain.features.market

import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol

interface MarketDataPort {
    suspend fun getUnderlyingPrice(symbol: Symbol): Money

    suspend fun getOptionMid(contract: OptionContract): Money
}
