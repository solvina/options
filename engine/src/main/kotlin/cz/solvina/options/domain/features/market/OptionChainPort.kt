package cz.solvina.options.domain.features.market

import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.Symbol
import java.time.LocalDate

interface OptionChainPort {
    suspend fun getAvailableExpirations(symbol: Symbol): Set<LocalDate>

    /**
     * Fetches candidate option quotes for [strategyId]. The strategy's [StrategyId] selects the
     * option right (PUT for bull put, CALL for bear call) and the strike-band tuning, so the OTM
     * band is built on the correct side of the underlying.
     */
    suspend fun getOptionChain(
        symbol: Symbol,
        expiry: LocalDate,
        underlyingPrice: Money,
        strategyId: StrategyId,
    ): List<OptionQuote>
}
