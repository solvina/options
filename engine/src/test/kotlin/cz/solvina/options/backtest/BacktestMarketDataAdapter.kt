package cz.solvina.options.backtest

import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.models.HistoricalBar
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol

/**
 * Serves market data from fixture CSVs for the simulated clock date.
 *
 * getUnderlyingPrice: returns the closing price on clock.currentDate(),
 * or the most recent prior close if that exact date has no bar (weekend /
 * market holiday).
 *
 * getOptionMid: implemented in Phase 4 (Black-Scholes).
 */
class BacktestMarketDataAdapter(
    private val clock: MutableClock,
) : MarketDataPort {
    private val cache = mutableMapOf<Symbol, List<HistoricalBar>>()

    override suspend fun getUnderlyingPrice(symbol: Symbol): Money {
        val bars = cache.getOrPut(symbol) { FixtureLoader.loadPriceBars(symbol) }
        val today = clock.currentDate()
        val bar =
            bars.lastOrNull { it.date <= today }
                ?: error("No price data for $symbol on or before $today — fixture may not cover this date")
        return Money(bar.close)
    }

    override suspend fun getOptionMid(contract: OptionContract): Money =
        error("getOptionMid not yet implemented — coming in Phase 4 (Black-Scholes)")
}
