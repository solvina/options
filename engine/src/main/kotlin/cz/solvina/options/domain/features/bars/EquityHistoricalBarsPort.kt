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
     *
     * [onChunk] is invoked with each batch's bars as they arrive, so callers can persist
     * incrementally — a long backfill's progress is durable and a single stalled/empty chunk never
     * loses the whole range. Defaults to a no-op.
     */
    suspend fun fetch5MinBarsForRange(
        symbol: Symbol,
        from: LocalDate,
        to: LocalDate,
        timeframe: Timeframe = Timeframe.FIVE_MIN,
        onChunk: suspend (List<FiveMinuteBar>) -> Unit = {},
    ): List<FiveMinuteBar>
}
