package cz.solvina.options.domain.features.bars

import cz.solvina.options.domain.models.Symbol

interface EquityHistoricalBarsPort {
    /** Returns the last [days] calendar days of completed 5-minute candles during RTH. */
    suspend fun fetch5MinBars(symbol: Symbol, days: Int): List<FiveMinuteBar>
}
