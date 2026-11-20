package cz.solvina.options.scanner

import cz.solvina.options.domain.features.scanner.CandidatePoolStorePort
import cz.solvina.options.domain.features.scanner.DailyCandidatePool
import cz.solvina.options.domain.models.Symbol
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DailyCandidatePoolTest {
    private class SettableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private class InMemoryStore : CandidatePoolStorePort {
        val rows = mutableMapOf<Pair<LocalDate, Symbol>, Instant>()

        override fun load(day: LocalDate) = rows.filterKeys { it.first == day }.mapKeys { it.key.second }

        override fun add(
            day: LocalDate,
            symbol: Symbol,
            firstSeenAt: Instant,
        ) {
            rows.putIfAbsent(day to symbol, firstSeenAt)
        }

        override fun remove(
            day: LocalDate,
            symbol: Symbol,
        ) {
            rows.remove(day to symbol)
        }

        override fun clear(day: LocalDate) {
            rows.keys.removeIf { it.first == day }
        }

        override fun deleteBefore(day: LocalDate) {
            rows.keys.removeIf { it.first.isBefore(day) }
        }
    }

    private val morning = Instant.parse("2026-11-20T15:00:00Z")
    private val dia = Symbol("DIA")
    private val qqq = Symbol("QQQ")

    @Test
    fun `a restart reloads the day's titles with their first-seen times`() {
        val clock = SettableClock(morning)
        val store = InMemoryStore()
        DailyCandidatePool(clock, store).apply {
            offer(dia)
            clock.now = morning.plusSeconds(600)
            offer(qqq)
            offer(dia) // already pooled: the first time stays
        }

        val restarted = DailyCandidatePool(clock, store)

        assertEquals(setOf(dia, qqq), restarted.symbols().toSet())
        assertEquals(morning, restarted.firstSeenAt(dia))
    }

    @Test
    fun `removals and the final clear reach the store, and a new day starts empty`() {
        val clock = SettableClock(morning)
        val store = InMemoryStore()
        val pool = DailyCandidatePool(clock, store)
        pool.offer(dia)
        pool.offer(qqq)
        pool.remove(dia)
        assertEquals(setOf(qqq), DailyCandidatePool(clock, store).symbols().toSet())

        clock.now = morning.plusSeconds(86_400)
        assertTrue(DailyCandidatePool(clock, store).symbols().isEmpty())
        assertTrue(store.rows.isEmpty(), "yesterday's rows are deleted on the first use of a new day")

        pool.offer(dia)
        pool.clear()
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `a failing store never breaks the in-memory pool`() {
        val broken =
            object : CandidatePoolStorePort by InMemoryStore() {
                override fun load(day: LocalDate): Map<Symbol, Instant> = error("db down")

                override fun add(
                    day: LocalDate,
                    symbol: Symbol,
                    firstSeenAt: Instant,
                ): Unit = error("db down")
            }
        val pool = DailyCandidatePool(SettableClock(morning), broken)

        pool.offer(dia)

        assertTrue(pool.contains(dia))
    }
}
