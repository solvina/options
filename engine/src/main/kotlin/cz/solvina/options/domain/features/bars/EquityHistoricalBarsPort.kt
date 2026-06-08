package cz.solvina.options.domain.features.bars

import cz.solvina.options.domain.models.Symbol
import java.time.LocalDate

interface EquityHistoricalBarsPort {
    /** Returns the last [days] calendar days of completed 5-minute candles during RTH. */
    suspend fun fetch5MinBars(
        symbol: Symbol,
        days: Int,
    ): List<FiveMinuteBar>

    /**
     * Fetches completed 5-minute RTH candles for an explicit date range.
     * Batches internally if the range exceeds the broker's per-request limit.
     * Results are written to the bar store by the implementation and returned sorted by time.
     */
    suspend fun fetch5MinBarsForRange(
        symbol: Symbol,
        from: LocalDate,
        to: LocalDate,
    ): List<FiveMinuteBar>
}
