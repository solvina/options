package cz.solvina.options.domain.features.bars

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** Aggregates exactly 60 × 5-second bars into one [FiveMinuteBar].
 *  Emits a complete bar aligned to 5-minute UTC boundaries.
 *
 *  All market open times (9:30 ET, 9:00 Berlin) are multiples of 300 s from the epoch, so
 *  IBKR real-time bars at 5-min boundaries satisfy `epochSecond % 300 == 0`. Waiting for that
 *  boundary at startup and after gaps prevents misaligned candles that would create duplicate
 *  or unmatchable entries when compared with historical 5-min bars. */
class BarAggregator(
    private val symbol: String,
) {
    private val buffer = ArrayDeque<RealTimeBar>(60)
    private var lastBarTime: Long? = null

    // Start in boundary-wait mode: discard bars until the first 5-min boundary so the
    // first emitted candle aligns with historical bars stored in InfluxDB.
    private var awaitingBoundary = true

    /**
     * Accepts a 5-second bar. Returns a completed [FiveMinuteBar] when the 60th bar arrives
     * after a 5-min boundary, or `null` if still accumulating / waiting to align.
     */
    fun add(bar: RealTimeBar): FiveMinuteBar? {
        // Gap detection: warn if bar arrived more than 6 seconds after the previous one
        lastBarTime?.let { prev ->
            val gapSec = bar.time.epochSecond - prev
            if (gapSec > 6) {
                logger.warn { "[$symbol] 5-sec bar gap detected: ${gapSec}s between consecutive bars — resetting aggregator" }
                buffer.clear()
                awaitingBoundary = true
            }
        }
        lastBarTime = bar.time.epochSecond

        // Wait until the end of a 5-min window (epochSecond % 300 == 0) before accumulating.
        // The bar at the boundary is the last bar of the current window; the next bar starts a
        // fresh window that will produce a properly-aligned candle.
        if (awaitingBoundary) {
            if (bar.time.epochSecond % 300 == 0L) awaitingBoundary = false
            return null
        }

        buffer.addLast(bar)

        return if (buffer.size == 60) {
            val completed = buildCandle()
            buffer.clear()
            completed
        } else {
            null
        }
    }

    private fun buildCandle(): FiveMinuteBar {
        val open = buffer.first().open
        val high = buffer.maxOf { it.high }
        val low = buffer.minOf { it.low }
        val close = buffer.last().close
        val volume = buffer.sumOf { it.volume }
        return FiveMinuteBar(
            time = buffer.last().time,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
        )
    }
}
