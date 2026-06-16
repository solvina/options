package cz.solvina.options.volatility

import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.volatility.HistoricalDataPort
import cz.solvina.options.domain.features.volatility.IvRankService
import cz.solvina.options.domain.features.volatility.IvRankStorePort
import cz.solvina.options.domain.features.volatility.StoredIvRank
import cz.solvina.options.domain.models.HistoricalBar
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class IvRankServiceTest {
    private val symbol = Symbol("AAPL")
    private val t0: Instant = Instant.parse("2026-06-16T12:00:00Z")

    private class MutableClock(
        var current: Instant,
    ) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    private class FakeStore(
        var preset: Map<Symbol, StoredIvRank> = emptyMap(),
    ) : IvRankStorePort {
        val saved = ConcurrentHashMap<Symbol, StoredIvRank>()

        override fun loadAll(): Map<Symbol, StoredIvRank> = preset

        override suspend fun save(
            symbol: Symbol,
            value: StoredIvRank,
        ) {
            saved[symbol] = value
        }
    }

    // IV bars: min=0.1, max=0.5, current(last)=0.3 → rank = (0.3-0.1)/(0.5-0.1)*100 = 50%
    private val fetchCount = AtomicInteger(0)
    private val histPort =
        object : HistoricalDataPort {
            override fun fetchDailyBars(
                symbol: Symbol,
                days: Int,
            ): Flow<HistoricalBar> {
                fetchCount.incrementAndGet()
                val bars =
                    listOf(0.2, 0.1, 0.5, 0.3).mapIndexed { i, iv ->
                        HistoricalBar(date = LocalDate.of(2026, 1, i + 1), close = BigDecimal("100"), iv = iv)
                    }
                return bars.asFlow()
            }
        }

    private fun service(
        clock: Clock,
        store: IvRankStorePort,
    ) = IvRankService(histPort, ScannerConfig(), clock, store, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `warm-load serves persisted fresh ranks without fetching`() =
        runTest {
            val store = FakeStore(preset = mapOf(symbol to StoredIvRank(rank = 72.0, currentIv = 0.4, calculatedAt = t0)))
            val svc = service(MutableClock(t0), store)
            svc.warmCache()

            val result = svc.getIvRank(symbol)

            assertEquals(72.0, result.rank, "should return the warm-loaded value")
            assertEquals(0, fetchCount.get(), "a fresh warm-loaded value must not trigger a fetch")
        }

    @Test
    fun `cold lookup computes from history and persists`() =
        runTest {
            val store = FakeStore()
            val svc = service(MutableClock(t0), store)

            val result = svc.getIvRank(symbol)

            assertEquals(50.0, result.rank, 1e-6)
            assertEquals(1, fetchCount.get())
            assertEquals(50.0, store.saved[symbol]!!.rank, 1e-6, "computed rank must be persisted")
        }

    @Test
    fun `stale value within window is served while a background refresh recomputes and persists`() =
        runTest {
            // Persisted value is 2h old: past the 60-min TTL but within the 48h serve-stale window.
            val store =
                FakeStore(
                    preset =
                        mapOf(
                            symbol to StoredIvRank(rank = 99.0, currentIv = 0.9, calculatedAt = t0.minus(2, ChronoUnit.HOURS)),
                        ),
                )
            val svc = service(MutableClock(t0), store)
            svc.warmCache()

            val result = svc.getIvRank(symbol)

            assertEquals(99.0, result.rank, 1e-6, "the stale value is served immediately")
            assertEquals(1, fetchCount.get(), "a background refresh fetched fresh history")
            assertEquals(50.0, store.saved[symbol]!!.rank, 1e-6, "the refreshed value was persisted")
        }
}
