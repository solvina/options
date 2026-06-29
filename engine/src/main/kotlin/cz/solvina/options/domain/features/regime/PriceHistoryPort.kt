package cz.solvina.options.domain.features.regime

import cz.solvina.options.domain.models.HistoricalBar
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.flow.Flow

/**
 * Daily price (TRADES) bars for regime computation — distinct from the IV-rank `HistoricalDataPort`
 * (whose bars carry implied volatility, not price). Implemented over the existing IBKR
 * `reqHistoricalData` path, so no new IBKR integration.
 */
interface PriceHistoryPort {
    fun fetchDailyPriceBars(
        symbol: Symbol,
        days: Int,
    ): Flow<HistoricalBar>
}
