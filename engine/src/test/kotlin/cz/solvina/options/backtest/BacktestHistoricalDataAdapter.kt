package cz.solvina.options.backtest

import cz.solvina.options.domain.features.volatility.HistoricalDataPort
import cz.solvina.options.domain.models.HistoricalBar
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * Serves IV history from fixture CSVs scoped to the simulated clock date.
 *
 * On each call, returns only bars with date <= clock.currentDate(), capped
 * at [days] entries counting backwards from that date — exactly matching
 * what IBKR would return for a live reqHistoricalData call on that day.
 */
class BacktestHistoricalDataAdapter(
    private val clock: MutableClock,
) : HistoricalDataPort {
    private val cache = mutableMapOf<Symbol, List<HistoricalBar>>()

    override fun fetchDailyBars(
        symbol: Symbol,
        days: Int,
    ): Flow<HistoricalBar> {
        val allBars = cache.getOrPut(symbol) { FixtureLoader.loadIvBars(symbol) }
        val today = clock.currentDate()
        return allBars
            .filter { it.date <= today }
            .takeLast(days)
            .asFlow()
    }
}
