package cz.solvina.options.domain.features.volatility

import cz.solvina.options.domain.models.HistoricalBar
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.flow.Flow

interface HistoricalDataPort {
    fun fetchDailyBars(
        symbol: Symbol,
        days: Int,
    ): Flow<HistoricalBar>
}
