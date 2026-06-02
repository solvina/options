package cz.solvina.options.bars

import cz.solvina.options.domain.features.bars.BarAggregator
import cz.solvina.options.domain.features.bars.RealTimeBar
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BarAggregatorTest {

    private fun bar(time: Instant, price: Double = 100.0) = RealTimeBar(
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
        val base = Instant.parse("2024-01-15T14:54:00Z")
        repeat(59) { i -> assertNull(aggregator.add(bar(base.plusSeconds(i * 5L)))) }
        assertNotNull(aggregator.add(bar(base.plusSeconds(59 * 5L))))
    }

    @Test
    fun `candle time is the close time of the last constituent bar not the open time`() {
        val aggregator = BarAggregator("AAPL")
        val openTime = Instant.parse("2024-01-15T14:54:00Z")  // first bar — candle open
        val closeTime = openTime.plusSeconds(59 * 5L)          // last bar  — candle close (14:58:55)

        repeat(59) { i -> aggregator.add(bar(openTime.plusSeconds(i * 5L))) }
        val completed = aggregator.add(bar(closeTime))

        assertNotNull(completed)
        assertEquals(
            closeTime, completed.time,
            "Candle.time must be the close time ($closeTime) not the open time ($openTime). " +
                "Callers use candle.time as 'the moment this bar completed', so it must reflect the last bar's timestamp."
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
