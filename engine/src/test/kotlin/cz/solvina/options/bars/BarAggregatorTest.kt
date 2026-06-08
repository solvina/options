package cz.solvina.options.bars

import cz.solvina.options.domain.features.bars.BarAggregator
import cz.solvina.options.domain.features.bars.RealTimeBar
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BarAggregatorTest {
    private fun bar(
        time: Instant,
        price: Double = 100.0,
    ) = RealTimeBar(
        time = time,
        open = price,
        high = price + 0.5,
        low = price - 0.5,
        close = price,
        volume = 1_000L,
    )

    @Test
    fun `candle is emitted only on the 60th bar`() {
        val aggregator = BarAggregator("AAPL")
        // 14:55:00Z has epochSecond % 300 == 0 — satisfies the boundary-wait exit condition.
        // That bar returns null but arms the aggregator; the next 60 bars accumulate normally.
        val boundary = Instant.parse("2024-01-15T14:55:00Z")
        assertNull(aggregator.add(bar(boundary))) // exits boundary-wait
        val base = boundary.plusSeconds(5) // 14:55:05 — first accumulated bar
        repeat(59) { i -> assertNull(aggregator.add(bar(base.plusSeconds(i * 5L)))) }
        assertNotNull(aggregator.add(bar(base.plusSeconds(59 * 5L)))) // 60th bar → candle emitted
    }

    @Test
    fun `candle time is the close time of the last constituent bar not the open time`() {
        val aggregator = BarAggregator("AAPL")
        // Boundary bar exits wait mode; subsequent 60 bars are the candle window.
        val boundary = Instant.parse("2024-01-15T14:55:00Z")
        aggregator.add(bar(boundary)) // exits boundary-wait, returns null
        val firstBarTime = boundary.plusSeconds(5) // 14:55:05 — candle open bar
        val lastBarTime = firstBarTime.plusSeconds(59 * 5L) // 15:00:00 — candle close bar

        repeat(59) { i -> aggregator.add(bar(firstBarTime.plusSeconds(i * 5L))) }
        val completed = aggregator.add(bar(lastBarTime))

        assertNotNull(completed)
        assertEquals(
            lastBarTime,
            completed.time,
            "Candle.time must be the close time ($lastBarTime) not the open time ($firstBarTime). " +
                "Callers use candle.time as 'the moment this bar completed', so it must reflect the last bar's timestamp.",
        )
    }

    @Test
    fun `gap in bars resets aggregator so partial candle is discarded`() {
        val aggregator = BarAggregator("AAPL")
        val base = Instant.parse("2024-01-15T14:54:00Z")

        repeat(30) { i -> aggregator.add(bar(base.plusSeconds(i * 5L))) }
        // Introduce a gap larger than 6 seconds — aggregator should reset
        val afterGap = base.plusSeconds(30 * 5L + 60L) // 60-second gap
        assertNull(aggregator.add(bar(afterGap)), "First bar after reset should not complete a candle")
    }
}
