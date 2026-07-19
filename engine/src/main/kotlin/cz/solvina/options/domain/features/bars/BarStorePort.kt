package cz.solvina.options.domain.features.bars

import cz.solvina.options.domain.models.Symbol
import java.time.Instant
import java.time.LocalDate

interface BarStorePort {
    suspend fun writeBar(
        symbol: Symbol,
        bar: FiveMinuteBar,
        timeframe: Timeframe = Timeframe.FIVE_MIN,
    )

    suspend fun writeBars(
        symbol: Symbol,
        bars: List<FiveMinuteBar>,
        timeframe: Timeframe = Timeframe.FIVE_MIN,
    )

    suspend fun readBars(
        symbol: Symbol,
        from: Instant,
        to: Instant,
        timeframe: Timeframe = Timeframe.FIVE_MIN,
    ): List<FiveMinuteBar>

    /** Returns the timestamp of the most recent stored bar for [symbol], or null if none. */
    suspend fun lastBarTime(
        symbol: Symbol,
        timeframe: Timeframe = Timeframe.FIVE_MIN,
    ): Instant?

    /**
     * Returns the number of bars stored per calendar day (UTC) for [symbol] at [timeframe]
     * across the inclusive date range [from]..[to]. Days with no data are included with a count of 0.
     */
    suspend fun coverageByDay(
        symbol: Symbol,
        from: LocalDate,
        to: LocalDate,
        timeframe: Timeframe = Timeframe.FIVE_MIN,
    ): Map<LocalDate, Int>

    /** One row per stored (symbol, timeframe) series: bar count and first/last bar time. */
    suspend fun seriesSummary(): List<SeriesSummary>
}

data class SeriesSummary(
    val symbol: String,
    val interval: String,
    val firstBar: Instant,
    val lastBar: Instant,
    val barCount: Long,
)
