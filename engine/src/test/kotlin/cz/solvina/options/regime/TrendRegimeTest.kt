package cz.solvina.options.regime

import cz.solvina.options.domain.features.regime.TrendRegime
import cz.solvina.options.domain.features.regime.classifyRegime
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/** SMA-fast/slow regime classification from an oldest-first close series. */
class TrendRegimeTest {
    @Test
    fun `uptrend when close above fast-sma above slow-sma`() {
        val rising = (1..250).map { BigDecimal(it) } // monotonically rising
        assertEquals(TrendRegime.UPTREND, classifyRegime(rising, 50, 200).regime)
    }

    @Test
    fun `downtrend when close below fast-sma below slow-sma`() {
        val falling = (1..250).map { BigDecimal(250 - it) } // monotonically falling
        assertEquals(TrendRegime.DOWNTREND, classifyRegime(falling, 50, 200).regime)
    }

    @Test
    fun `neutral when fewer than slow-period bars`() {
        val short = (1..100).map { BigDecimal(it) } // < 200 bars
        assertEquals(TrendRegime.NEUTRAL, classifyRegime(short, 50, 200).regime)
    }

    @Test
    fun `neutral when flat (no alignment)`() {
        val flat = List(250) { BigDecimal(100) } // close == smaFast == smaSlow
        assertEquals(TrendRegime.NEUTRAL, classifyRegime(flat, 50, 200).regime)
    }
}
