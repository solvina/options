package cz.solvina.options.adapters.outbound.ibkr.market

import cz.solvina.options.adapters.outbound.ibkr.market.SnapshotWaitStats.Companion.replay
import cz.solvina.options.adapters.outbound.ibkr.registry.MarketDataSnapshot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotWaitStatsTest {
    private val rule = SnapshotWaitStats.Rule(1500, 1000)
    private val ready = SnapshotReady.OPTION_QUOTE
    private val prices = MarketDataSnapshot(bid = 1.0, ask = 1.2)
    private val full = prices.copy(delta = -0.25)

    @Test
    fun `prices at once and a delta at 4s - the quiet rule would have missed the delta`() {
        val ticks = listOf(100L to MarketDataSnapshot(bid = 1.0), 150L to prices, 4000L to full)

        val r = replay(rule, ticks, 4000, full, ready)

        assertEquals(1500, r.returnAtMs)
        assertEquals(2500, r.savedMs)
        assertTrue(r.lostDelta && r.lostReady)
        assertFalse(r.lostBid || r.lostAsk)
        assertEquals(4000, r.lateArrivalMs)
    }

    @Test
    fun `a one-sided quote that never completes is cut at the quiet point and loses nothing`() {
        val oneSided = MarketDataSnapshot(bid = 0.0, ask = 0.05, delta = -0.02)
        val ticks = listOf(120L to MarketDataSnapshot(ask = 0.05), 200L to oneSided)

        val r = replay(rule, ticks, 5000, oneSided, ready)

        assertEquals(1500, r.returnAtMs)
        assertEquals(3500, r.savedMs)
        assertFalse(r.lostAnything)
    }

    @Test
    fun `steady ticks push the return past the cap - no early return`() {
        val ticks = (0L..4800L step 400L).map { it to prices }

        assertEquals(0, replay(rule, ticks, 5000, prices, ready).savedMs)
    }

    @Test
    fun `a request already ready before the rule fires, or one with no ticks, is unchanged`() {
        assertEquals(0, replay(rule, listOf(300L to full), 300, full, ready).savedMs)
        assertEquals(0, replay(rule, emptyList(), 5000, MarketDataSnapshot(), ready).savedMs)
    }

    @Test
    fun `the purpose maps to a category`() {
        assertEquals("strike", SnapshotWaitStats.category("scanner candidate-strike greeks (conId=1 route=PSE)"))
        assertEquals("combo", SnapshotWaitStats.category("combo BAG quote (scanner achievable-credit check)"))
        assertEquals("underlying", SnapshotWaitStats.category("underlying price (scanner/exit/strategy pricing)"))
        assertEquals("other", SnapshotWaitStats.category("option live mid (exit/close decision, no BS fallback)"))
        assertEquals("", SnapshotWaitStats().drain())
    }
}
