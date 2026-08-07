package cz.solvina.options.bars

import cz.solvina.options.domain.features.bars.Candle
import cz.solvina.options.domain.features.bars.DailyBarRefreshService
import cz.solvina.options.domain.features.bars.EquityHistoricalBarsPort
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignment
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignmentPort
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class DailyBarRefreshServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-07T21:30:00Z"), ZoneOffset.UTC)

    @Test
    fun `refreshes each assigned symbol once over the lookback window`() =
        runTest {
            val fetcher = RecordingFetcher()
            val service = service(fetcher, assignments = listOf("AAPL" to Timeframe.DAILY, "MSFT" to Timeframe.DAILY))

            val result = service.refreshOnce()

            assertEquals(2, result.symbols)
            assertEquals(
                listOf(
                    Fetch(Symbol("AAPL"), LocalDate.of(2026, 6, 23), LocalDate.of(2026, 8, 7), Timeframe.DAILY),
                    Fetch(Symbol("MSFT"), LocalDate.of(2026, 6, 23), LocalDate.of(2026, 8, 7), Timeframe.DAILY),
                ),
                fetcher.calls,
            )
        }

    @Test
    fun `a symbol assigned twice is fetched once`() =
        runTest {
            val fetcher = RecordingFetcher()
            // Two strategies on the same symbol and timeframe need the same bars — asking IBKR twice
            // would spend pacing budget for nothing.
            val service = service(fetcher, assignments = listOf("AAPL" to Timeframe.DAILY, "AAPL" to Timeframe.DAILY))

            service.refreshOnce()

            assertEquals(1, fetcher.calls.size)
        }

    @Test
    fun `a failing symbol does not stop the ones behind it`() =
        runTest {
            val fetcher = RecordingFetcher(failOn = setOf(Symbol("AAPL")))
            val service = service(fetcher, assignments = listOf("AAPL" to Timeframe.DAILY, "MSFT" to Timeframe.DAILY))

            val result = service.refreshOnce()

            assertEquals(1, result.failures.size)
            assertEquals(listOf(Symbol("MSFT")), fetcher.calls.map { it.symbol })
        }

    @Test
    fun `no enabled assignments means no broker requests at all`() =
        runTest {
            val fetcher = RecordingFetcher()

            val result = service(fetcher, assignments = emptyList()).refreshOnce()

            assertEquals(0, result.symbols)
            assertEquals(0, fetcher.calls.size)
        }

    private fun service(
        fetcher: RecordingFetcher,
        assignments: List<Pair<String, Timeframe>>,
    ) = DailyBarRefreshService(
        assignments = FakeAssignments(assignments),
        equityHistoricalBars = fetcher,
        clock = clock,
        lookbackDays = 45,
        pacingMs = 10_000, // virtual time under runTest — pacing costs the test nothing
    )

    private data class Fetch(
        val symbol: Symbol,
        val from: LocalDate,
        val to: LocalDate,
        val timeframe: Timeframe,
    )

    private class RecordingFetcher(
        private val failOn: Set<Symbol> = emptySet(),
    ) : EquityHistoricalBarsPort {
        val calls = mutableListOf<Fetch>()

        override suspend fun fetch5MinBars(
            symbol: Symbol,
            days: Int,
        ) = emptyList<Candle>()

        override suspend fun fetch5MinBarsForRange(
            symbol: Symbol,
            from: LocalDate,
            to: LocalDate,
            timeframe: Timeframe,
            onChunk: suspend (List<Candle>) -> Unit,
        ): List<Candle> {
            if (symbol in failOn) error("IBKR request failed")
            calls += Fetch(symbol, from, to, timeframe)
            return emptyList()
        }
    }

    private class FakeAssignments(
        pairs: List<Pair<String, Timeframe>>,
    ) : StrategyAssignmentPort {
        private val rows =
            pairs.map { (symbol, timeframe) ->
                StrategyAssignment(
                    id = UUID.randomUUID(),
                    strategyId = "mfi_oversold",
                    symbol = Symbol(symbol),
                    timeframe = timeframe,
                    enabled = true,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                )
            }

        override fun findAll() = rows

        override fun findEnabled() = rows

        override fun findById(id: UUID) = rows.firstOrNull { it.id == id }

        override fun save(assignment: StrategyAssignment) = assignment

        override fun delete(id: UUID) = true
    }
}
