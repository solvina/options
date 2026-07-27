package cz.solvina.options.market

import cz.solvina.options.domain.features.market.MarketDataTypeTracker
import cz.solvina.options.domain.features.market.MarketDataTypeTracker.Feed
import cz.solvina.options.domain.models.Symbol
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.test.assertEquals

class MarketDataTypeTrackerTest {
    private val tracker = MarketDataTypeTracker(Clock.systemUTC())
    private val sym = Symbol("EXV1")

    @Test
    fun `unknown until recorded`() {
        assertEquals(Feed.UNKNOWN, tracker.feedFor(Symbol("NOPE")))
    }

    @Test
    fun `maps IBKR market-data types to feeds`() {
        tracker.recordType(sym, 1)
        assertEquals(Feed.LIVE, tracker.feedFor(sym))
        tracker.recordType(sym, 3)
        assertEquals(Feed.DELAYED, tracker.feedFor(sym))
        tracker.recordType(sym, 2)
        assertEquals(Feed.FROZEN, tracker.feedFor(sym))
        tracker.recordType(sym, 4)
        assertEquals(Feed.DELAYED_FROZEN, tracker.feedFor(sym))
    }

    @Test
    fun `real-time bars flowing is authoritative live, rejection is blind`() {
        tracker.recordLiveBars(sym)
        assertEquals(Feed.LIVE, tracker.feedFor(sym))

        tracker.recordBlind(sym, "bars rejected (code=354)")
        assertEquals(Feed.BLIND, tracker.feedFor(sym))
        assertEquals("bars rejected (code=354)", tracker.snapshot()[sym.value]?.detail)
    }
}
