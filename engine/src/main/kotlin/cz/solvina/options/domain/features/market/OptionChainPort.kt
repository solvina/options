package cz.solvina.options.domain.features.market

import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.Symbol
import java.time.LocalDate

interface OptionChainPort {
    suspend fun getAvailableExpirations(symbol: Symbol): Set<LocalDate>

    suspend fun getOptionChain(
        symbol: Symbol,
        expiry: LocalDate,
        underlyingPrice: Money,
    ): List<OptionQuote>
}
