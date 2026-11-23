package cz.solvina.options.market

import cz.solvina.options.adapters.outbound.ibkr.market.PutStrikeProbe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PutStrikeProbeTest {
    private fun k(v: Int) = BigDecimal(v)

    // KO-like grid: spot ~70, $1 strikes from 55 to 69 (the OTM band).
    private val grid = (55..69).map { k(it) }

    @Test
    fun `probes spread across the whole band, both ends included`() {
        val probes = PutStrikeProbe.probeStrikes(grid, 5)

        assertEquals(listOf(k(55), k(59), k(62), k(66), k(69)), probes)
    }

    @Test
    fun `a band smaller than the probe count is probed in full`() {
        assertEquals(listOf(k(1), k(2), k(3)), PutStrikeProbe.probeStrikes(listOf(k(3), k(1), k(2)), 7))
    }

    @Test
    fun `the bracket straddles the target between the probes either side of it`() {
        val probes = listOf(k(69) to 0.45, k(66) to 0.31, k(62) to 0.14, k(59) to 0.06, k(55) to 0.02)

        val b = PutStrikeProbe.bracket(probes, 0.25)!!

        assertEquals(k(66), b.upper)
        assertEquals(k(62), b.lower)
    }

    @Test
    fun `refine prices the strikes inside the bracket, nearest the interpolated target first`() {
        val b = PutStrikeProbe.Bracket(upper = k(66), upperDelta = 0.31, lower = k(62), lowerDelta = 0.14)

        val refined = PutStrikeProbe.refineStrikes(grid, b, 2, targetDelta = 0.25)

        // Target interpolates to ~64.6 → 65 and 64; the probed ends are not re-priced.
        assertEquals(listOf(k(65), k(64)), refined)
    }

    @Test
    fun `probes without a live delta are ignored, and none at all means no bracket`() {
        assertNull(PutStrikeProbe.bracket(listOf(k(60) to null, k(65) to null), 0.25))

        val b = PutStrikeProbe.bracket(listOf(k(66) to null, k(64) to 0.30, k(60) to 0.10), 0.25)!!
        assertEquals(k(64), b.upper)
        assertEquals(k(60), b.lower)
    }

    @Test
    fun `when every probe is above target the bracket opens on the far-OTM side`() {
        val b = PutStrikeProbe.bracket(listOf(k(69) to 0.6, k(62) to 0.4, k(55) to 0.3), 0.25)!!

        assertEquals(k(55), b.upper)
        assertNull(b.lower)
        // Nothing further OTM inside the band: the refine round adds nothing new.
        assertTrue(PutStrikeProbe.refineStrikes(grid, b, 7, 0.25).isEmpty())
    }
}

class PutStrikeAimTest {
    @Test
    fun `aim lands where Black-Scholes gives the target delta`() {
        val aim =
            cz.solvina.options.adapters.outbound.ibkr.market.PutStrikeAim
                .strikeForDelta(spot = 70.0, t = 41 / 365.0, sigma = 0.20, targetDelta = 0.25)
        val d =
            kotlin.math.abs(
                cz.solvina.options.domain.features.market.BlackScholes
                    .putDelta(70.0, aim, 41 / 365.0, 0.04, 0.20),
            )

        assertEquals(0.25, d, 1e-4)
        // A 25-delta, 41-DTE put on a 20%-vol name sits a few % OTM — nowhere near 25% OTM.
        assertTrue(aim in 65.0..68.5, "aim=$aim")
    }

    @Test
    fun `higher volatility pushes the aim further OTM`() {
        val aim = cz.solvina.options.adapters.outbound.ibkr.market.PutStrikeAim
        val calm = aim.strikeForDelta(100.0, 0.11, 0.20, 0.25)
        val wild = aim.strikeForDelta(100.0, 0.11, 0.60, 0.25)

        assertTrue(wild < calm)
    }

    @Test
    fun `coverage needs the live deltas to straddle the target or one close hit`() {
        val aim = cz.solvina.options.adapters.outbound.ibkr.market.PutStrikeAim

        assertTrue(aim.covers(listOf(0.31, 0.27, 0.19), 0.25))
        assertTrue(aim.covers(listOf(0.33, 0.27), 0.25)) // 0.27 within tolerance
        assertTrue(!aim.covers(listOf(0.45, 0.38, 0.33), 0.25))
        assertTrue(!aim.covers(emptyList(), 0.25))
    }
}

class CallStrikeSearchTest {
    private val aim = cz.solvina.options.adapters.outbound.ibkr.market.PutStrikeAim
    private val call = cz.solvina.options.domain.models.OptionType.CALL

    @Test
    fun `call aim lands above spot where Black-Scholes gives the target delta`() {
        val k = aim.strikeForDelta(call, spot = 70.0, t = 41 / 365.0, sigma = 0.20, targetDelta = 0.25)
        val d =
            cz.solvina.options.domain.features.market.BlackScholes
                .delta(call, 70.0, k, 41 / 365.0, 0.04, 0.20)

        assertEquals(0.25, d, 1e-4)
        // A 25-delta call sits a few % above spot — not 25% above it (the old guess).
        assertTrue(k in 71.5..75.0, "aim=$k")
    }

    @Test
    fun `the put probe brackets a call chain once strikes are mirrored`() {
        // Call |delta| falls as the strike rises: 101→0.45, 104→0.32, 107→0.20, 110→0.12.
        val calls = listOf(101, 104, 107, 110).map { BigDecimal(it) }
        val deltas = mapOf(101 to 0.45, 104 to 0.32, 107 to 0.20, 110 to 0.12)
        val mirrored = calls.map { it.negate() to deltas.getValue(it.toInt()) }

        val b = assertNotNullBracket(PutStrikeProbe.bracket(mirrored, 0.25))

        // Upper = nearer-the-money side (|delta| above target), lower = further OTM.
        assertEquals(BigDecimal(104), b.upper!!.negate())
        assertEquals(BigDecimal(107), b.lower!!.negate())
        val listed = (101..110).map { BigDecimal(it) }
        val refined = PutStrikeProbe.refineStrikes(listed.map { it.negate() }, b, 2, 0.25).map { it.negate() }
        assertEquals(setOf(BigDecimal(105), BigDecimal(106)), refined.toSet())
    }

    private fun assertNotNullBracket(b: PutStrikeProbe.Bracket?): PutStrikeProbe.Bracket = kotlin.test.assertNotNull(b)
}
