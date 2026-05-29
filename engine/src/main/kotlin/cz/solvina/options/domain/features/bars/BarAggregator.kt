package cz.solvina.options.domain.features.bars

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/** Aggregates exactly 60 × 5-second bars into one [FiveMinuteBar].
 *  Emits a complete bar after every 60th input bar. */
class BarAggregator(
    private val symbol: String,
) {
    private val buffer = ArrayDeque<RealTimeBar>(60)
    private var lastBarTime: Long? = null

    /**
     * Accepts a 5-second bar. Returns a completed [FiveMinuteBar] when the 60th bar arrives,
     * or `null` if still accumulating.
     */
    fun add(bar: RealTimeBar): FiveMinuteBar? {
        // Gap detection: warn if bar arrived more than 6 seconds after the previous one
        lastBarTime?.let { prev ->
            val gapSec = bar.time.epochSecond - prev
            if (gapSec > 6) {
                logger.warn { "[$symbol] 5-sec bar gap detected: ${gapSec}s between consecutive bars — resetting aggregator" }
                buffer.clear()
            }
        }
        lastBarTime = bar.time.epochSecond

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
            time = buffer.first().time,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
        )
    }
}
