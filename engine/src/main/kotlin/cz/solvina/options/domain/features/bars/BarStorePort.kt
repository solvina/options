package cz.solvina.options.domain.features.bars

import cz.solvina.options.domain.models.Symbol
import java.time.Instant
import java.time.LocalDate

interface BarStorePort {
    suspend fun writeBar(
        symbol: Symbol,
        bar: FiveMinuteBar,
    )

    suspend fun writeBars(
        symbol: Symbol,
        bars: List<FiveMinuteBar>,
    )

    suspend fun readBars(
        symbol: Symbol,
        from: Instant,
        to: Instant,
    ): List<FiveMinuteBar>

    /** Returns the timestamp of the most recent stored bar for [symbol], or null if none. */
    suspend fun lastBarTime(symbol: Symbol): Instant?

    /**
     * Returns the number of 5-min bars stored per calendar day (UTC) for [symbol]
     * across the inclusive date range [from]..[to]. Days with no data are included
     * with a count of 0.
     */
    suspend fun coverageByDay(
        symbol: Symbol,
        from: LocalDate,
        to: LocalDate,
    ): Map<LocalDate, Int>
}
