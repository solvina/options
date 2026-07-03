package cz.solvina.options.ibkr

import cz.solvina.options.adapters.outbound.ibkr.TradingHoursCache
import cz.solvina.options.domain.features.universe.DaySession
import cz.solvina.options.domain.models.Symbol
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TradingHoursCacheTest {
    private fun parse(raw: String) = TradingHoursCache.parseLiquidHours(raw)

    @Test
    fun `parses a holiday CLOSED day and the next regular session`() {
        val parsed = parse("20260703:CLOSED;20260706:0930-20260706:1600")

        assertEquals(DaySession.Closed, parsed[LocalDate.of(2026, 7, 3)])
        assertEquals(
            DaySession.Open(LocalTime.of(9, 30), LocalTime.of(16, 0)),
            parsed[LocalDate.of(2026, 7, 6)],
        )
    }

    @Test
    fun `parses a half-day early close`() {
        // Day after Thanksgiving: US equities close at 13:00.
        val parsed = parse("20261127:0930-20261127:1300")

        assertEquals(
            DaySession.Open(LocalTime.of(9, 30), LocalTime.of(13, 0)),
            parsed[LocalDate.of(2026, 11, 27)],
        )
    }

    @Test
    fun `parses the older date-less end-time format`() {
        val parsed = parse("20260706:0930-1600")

        assertEquals(
            DaySession.Open(LocalTime.of(9, 30), LocalTime.of(16, 0)),
            parsed[LocalDate.of(2026, 7, 6)],
        )
    }

    @Test
    fun `an open window wins over a CLOSED marker for the same date`() {
        assertEquals(
            DaySession.Open(LocalTime.of(9, 30), LocalTime.of(16, 0)),
            parse("20260706:CLOSED;20260706:0930-20260706:1600")[LocalDate.of(2026, 7, 6)],
        )
        assertEquals(
            DaySession.Open(LocalTime.of(9, 30), LocalTime.of(16, 0)),
            parse("20260706:0930-20260706:1600;20260706:CLOSED")[LocalDate.of(2026, 7, 6)],
        )
    }

    @Test
    fun `skips blank and unparseable segments`() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("garbage;;;").isEmpty())
        // A valid segment survives alongside junk.
        val parsed = parse("nonsense;20260706:0930-20260706:1600")
        assertEquals(1, parsed.size)
    }

    @Test
    fun `update then sessionFor round-trips per symbol and ignores blank input`() {
        val cache = TradingHoursCache()
        val amd = Symbol("AMD")

        cache.update(amd, "20260703:CLOSED;20260706:0930-20260706:1600")
        assertEquals(DaySession.Closed, cache.sessionFor(amd, LocalDate.of(2026, 7, 3)))
        assertEquals(
            DaySession.Open(LocalTime.of(9, 30), LocalTime.of(16, 0)),
            cache.sessionFor(amd, LocalDate.of(2026, 7, 6)),
        )
        // Unknown symbol / unknown date -> null (caller falls back).
        assertNull(cache.sessionFor(Symbol("NVDA"), LocalDate.of(2026, 7, 6)))
        assertNull(cache.sessionFor(amd, LocalDate.of(2026, 7, 7)))

        // Blank input must not wipe an existing calendar.
        cache.update(amd, null)
        cache.update(amd, "   ")
        assertEquals(DaySession.Closed, cache.sessionFor(amd, LocalDate.of(2026, 7, 3)))
    }
}
