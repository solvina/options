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
