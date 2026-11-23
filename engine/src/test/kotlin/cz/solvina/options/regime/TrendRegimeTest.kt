package cz.solvina.options.regime

import cz.solvina.options.domain.features.regime.DirectionalBias
import cz.solvina.options.domain.features.regime.TrendRegime
import cz.solvina.options.domain.features.regime.classifyRegime
import cz.solvina.options.domain.features.regime.computeRsi
import cz.solvina.options.domain.features.regime.directionalBias
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // ---- RSI ----

    @Test
    fun `rsi null with insufficient history`() {
        assertNull(computeRsi((1..10).map { BigDecimal(it) }, 14))
    }

    @Test
    fun `rsi is 100 for monotonic rise, low for monotonic fall`() {
        assertEquals(BigDecimal("100.00"), computeRsi((1..40).map { BigDecimal(it) }, 14))
        val falling = computeRsi((1..40).map { BigDecimal(40 - it) }, 14)!!
        assertTrue(falling < BigDecimal("5"), "monotonic fall should be deeply oversold, was $falling")
    }

    // ---- directional bias (the gate decision) ----

    @Test
    fun `uptrend not overbought is bullish (bull put)`() {
        assertEquals(DirectionalBias.BULLISH, directionalBias(TrendRegime.UPTREND, BigDecimal("55"), 70.0, 30.0))
    }

    @Test
    fun `downtrend not oversold is bearish (bear call)`() {
        assertEquals(DirectionalBias.BEARISH, directionalBias(TrendRegime.DOWNTREND, BigDecimal("45"), 70.0, 30.0))
    }

    @Test
    fun `neutral oversold fades the drop to bullish, overbought fades the rally to bearish`() {
        assertEquals(DirectionalBias.BULLISH, directionalBias(TrendRegime.NEUTRAL, BigDecimal("25"), 70.0, 30.0))
        assertEquals(DirectionalBias.BEARISH, directionalBias(TrendRegime.NEUTRAL, BigDecimal("80"), 70.0, 30.0))
    }

    @Test
    fun `stretched-with-trend is neutral (do not force a side)`() {
        // uptrend but overbought, and downtrend but oversold → stand aside
        assertEquals(DirectionalBias.NEUTRAL, directionalBias(TrendRegime.UPTREND, BigDecimal("80"), 70.0, 30.0))
        assertEquals(DirectionalBias.NEUTRAL, directionalBias(TrendRegime.DOWNTREND, BigDecimal("25"), 70.0, 30.0))
    }

    @Test
    fun `null rsi falls back to trend only`() {
        assertEquals(DirectionalBias.BULLISH, directionalBias(TrendRegime.UPTREND, null, 70.0, 30.0))
        assertEquals(DirectionalBias.BEARISH, directionalBias(TrendRegime.DOWNTREND, null, 70.0, 30.0))
        assertEquals(DirectionalBias.NEUTRAL, directionalBias(TrendRegime.NEUTRAL, null, 70.0, 30.0))
    }

    @Test
    fun `oversold below both averages is a falling knife, not a dip`() {
        assertEquals(
            DirectionalBias.BEARISH,
            directionalBias(TrendRegime.NEUTRAL, BigDecimal("28"), 70.0, 30.0, belowBothAverages = true),
        )
        assertEquals(
            DirectionalBias.BEARISH,
            directionalBias(TrendRegime.DOWNTREND, BigDecimal("25"), 70.0, 30.0, belowBothAverages = true),
        )
        // Oversold between the averages is still faded (bull put).
        assertEquals(DirectionalBias.BULLISH, directionalBias(TrendRegime.NEUTRAL, BigDecimal("28"), 70.0, 30.0))
    }

    @Test
    fun `overbought above both averages is a breakout, not a top`() {
        assertEquals(
            DirectionalBias.BULLISH,
            directionalBias(TrendRegime.UPTREND, BigDecimal("80"), 70.0, 30.0, aboveBothAverages = true),
        )
        assertEquals(
            DirectionalBias.BULLISH,
            directionalBias(TrendRegime.NEUTRAL, BigDecimal("75"), 70.0, 30.0, aboveBothAverages = true),
        )
    }

    @Test
    fun `a crash through both averages before the cross reads bearish (UNH 2026-11-23)`() {
        // 230 bars climbing to ~400, then a two-week slide to ~334: SMA50 still above SMA200,
        // close below both, RSI deep oversold.
        val climb = (0 until 230).map { BigDecimal(300 + it * 100.0 / 230) }
        val slide = (1..14).map { BigDecimal(400 - it * 4.7) }
        val r = classifyRegime(climb + slide, 50, 200)

        assertEquals(TrendRegime.NEUTRAL, r.regime)
        assertTrue(r.smaFast!! > r.smaSlow!! && r.lastClose!! < r.smaSlow!!)
        assertEquals(DirectionalBias.BEARISH, r.bias)
    }
}
