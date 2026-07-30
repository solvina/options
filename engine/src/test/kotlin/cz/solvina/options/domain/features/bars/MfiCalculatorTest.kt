package cz.solvina.options.domain.features.bars

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MfiCalculatorTest {
    private fun candle(
        high: Double,
        low: Double,
        close: Double,
        volume: Long,
    ) = Candle(Instant.EPOCH, open = close, high = high, low = low, close = close, volume = volume)

    @Test
    fun `no value until the window has filled`() {
        val mfi = MoneyFlow(3)
        // The first bar only sets the previous typical price — it classifies nothing.
        mfi.update(10.0, 9.0, 9.5, 100.0)
        assertNull(mfi.value)
        mfi.update(11.0, 10.0, 10.5, 100.0)
        assertNull(mfi.value, "one classified bar is not a 3-bar window")
        mfi.update(12.0, 11.0, 11.5, 100.0)
        assertNull(mfi.value)
        mfi.update(13.0, 12.0, 12.5, 100.0)
        assertNotNull(mfi.value, "third classified bar completes the window")
    }

    @Test
    fun `an unbroken advance is 100 and an unbroken decline is 0`() {
        val up = MoneyFlow(3)
        listOf(10.0, 11.0, 12.0, 13.0).forEach { up.update(it + 0.5, it - 0.5, it, 1_000.0) }
        assertEquals(100.0, up.value!!, 1e-9)

        val down = MoneyFlow(3)
        listOf(13.0, 12.0, 11.0, 10.0).forEach { down.update(it + 0.5, it - 0.5, it, 1_000.0) }
        assertEquals(0.0, down.value!!, 1e-9)
    }

    @Test
    fun `a flat or zero-volume window is neutral, not maximally oversold`() {
        // Both sides of the flow are zero here. Reporting 0 would fire an oversold entry on a
        // symbol that simply did not move — the same trap WilderRsi avoids for 0/0.
        val flat = MoneyFlow(3)
        repeat(5) { flat.update(10.5, 9.5, 10.0, 1_000.0) }
        assertEquals(50.0, flat.value!!, 1e-9)

        val noVolume = MoneyFlow(3)
        listOf(10.0, 11.0, 10.0, 11.0, 10.0).forEach { noVolume.update(it + 0.5, it - 0.5, it, 0.0) }
        assertEquals(50.0, noVolume.value!!, 1e-9)
    }

    @Test
    fun `volume weighting is what separates MFI from RSI`() {
        // Identical price path; only the volume on the down bars differs. Heavy selling must pull
        // MFI below the balanced case, which a price-only oscillator could not distinguish.
        fun run(downVolume: Double): Double {
            val m = MoneyFlow(4)
            val path = listOf(10.0, 11.0, 10.0, 11.0, 10.0)
            var prev = path.first()
            m.update(path[0] + 0.5, path[0] - 0.5, path[0], 1_000.0)
            for (px in path.drop(1)) {
                m.update(px + 0.5, px - 0.5, px, if (px < prev) downVolume else 1_000.0)
                prev = px
            }
            return m.value!!
        }
        assertTrue(run(5_000.0) < run(1_000.0), "heavier down-volume must lower MFI")
    }

    @Test
    fun `batch helper agrees with the rolling accumulator`() {
        val candles =
            listOf(
                candle(10.0, 9.0, 9.5, 1_000),
                candle(11.0, 10.0, 10.5, 1_200),
                candle(10.5, 9.5, 9.8, 900),
                candle(12.0, 11.0, 11.5, 1_500),
                candle(11.0, 10.0, 10.2, 800),
            )
        val rolling = MoneyFlow(3)
        candles.forEach { rolling.update(it.high, it.low, it.close, it.volume.toDouble()) }
        val batch = MfiCalculator.last(candles, 3)
        assertNotNull(batch)
        assertTrue(abs(batch - rolling.value!!) < 1e-9)
    }

    @Test
    fun `a non-positive period yields no value rather than dividing by zero`() {
        assertNull(MfiCalculator.last(listOf(candle(10.0, 9.0, 9.5, 100)), 0))
    }
}
