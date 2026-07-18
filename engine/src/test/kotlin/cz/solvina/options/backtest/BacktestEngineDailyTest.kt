package cz.solvina.options.backtest

import cz.solvina.options.domain.features.backtest.BacktestAccountView
import cz.solvina.options.domain.features.backtest.BacktestEngine
import cz.solvina.options.domain.features.backtest.BacktestSignal
import cz.solvina.options.domain.features.backtest.BacktestableStrategy
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Daily-timeframe swing semantics (holdOvernight): a signal emitted on day N's (only) bar must
 * stay pending into day N+1 and fill there — nightly pending-clearing previously expired it before
 * it ever saw a next bar, so a daily backtest could not produce a single trade. An entry that
 * doesn't fill on day N+1 must then expire (good-for-next-session, not good-till-cancelled).
 */
class BacktestEngineDailyTest {
    private val symbol = Symbol("TEST")
    private val ny = ZoneId.of("America/New_York")

    private fun dailyBar(
        date: LocalDate,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
    ) = FiveMinuteBar(
        // 16:00 New York close-stamp, matching how daily bars group into one trading day.
        time = date.atTime(16, 0).atZone(ny).toInstant(),
        open = open,
        high = high,
        low = low,
        close = close,
        volume = 1_000,
    )

    /** Emits one bracket (entry 100 / stop 97 / target 106) on the first bar it sees. */
    private class OneShotStrategy : BacktestableStrategy {
        val fills = mutableListOf<Pair<BigDecimal, Instant>>()
        val expired = mutableListOf<String>()
        val closed = mutableListOf<String>()
        private var emitted = false

        override fun initialize(
            symbols: List<Symbol>,
            warmupBars: Map<Symbol, List<FiveMinuteBar>>,
        ) {}

        override fun onBar(
            symbol: Symbol,
            bar: FiveMinuteBar,
            account: BacktestAccountView,
        ): List<BacktestSignal> {
            if (emitted) return emptyList()
            emitted = true
            return listOf(
                BacktestSignal.OpenBracket(
                    tradeId = "t1",
                    symbol = symbol,
                    shares = 10,
                    entryPrice = BigDecimal("100.00"),
                    stopLossPrice = BigDecimal("97.00"),
                    profitTargetPrice = BigDecimal("106.00"),
                ),
            )
        }

        override fun onEntryFilled(
            tradeId: String,
            fillPrice: BigDecimal,
            filledAt: Instant,
        ) {
            fills.add(fillPrice to filledAt)
        }

        override fun onEntryExpired(tradeId: String) {
            expired.add(tradeId)
        }

        override fun onPositionClosed(
            tradeId: String,
            closePrice: BigDecimal,
            closeReason: String,
            closedAt: Instant,
            highestSeen: BigDecimal,
            lowestSeen: BigDecimal,
        ) {
            closed.add(closeReason)
        }

        override fun trades(): List<*> = closed
    }

    private fun engineOver(bars: List<FiveMinuteBar>): BacktestEngine {
        val store = mockk<BarStorePort>()
        coEvery { store.readBars(symbol, any(), any(), Timeframe.DAILY) } returns bars
        return BacktestEngine(store)
    }

    @Test
    fun `holdOvernight daily - signal from day N fills on day N+1 and reaches its target`() =
        runTest {
            val d1 = LocalDate.of(2024, 3, 4) // signal emitted on this bar
            val bars =
                listOf(
                    dailyBar(d1, 99.0, 100.5, 98.5, 100.0),
                    dailyBar(d1.plusDays(1), 100.5, 101.0, 99.5, 100.8), // gap over entry → fill at open
                    dailyBar(d1.plusDays(2), 101.0, 107.0, 100.5, 106.5), // target 106 hit
                )
            val strategy = OneShotStrategy()

            val result =
                engineOver(bars).run<String>(
                    BacktestEngine.Request(
                        symbols = listOf(symbol),
                        from = d1,
                        to = d1.plusDays(2),
                        holdOvernight = true,
                        timeframe = Timeframe.DAILY,
                    ),
                    strategy,
                )

            assertEquals(1, strategy.fills.size, "day-N signal must fill on day N+1")
            assertEquals(0, BigDecimal("100.5").compareTo(strategy.fills.first().first), "gap-up fills at open")
            assertEquals(listOf("profit_target"), strategy.closed)
            assertEquals(1, result.summary.winCount)
            assertTrue(strategy.expired.isEmpty(), "a filled entry must not also expire")
        }

    @Test
    fun `holdOvernight daily - unfilled entry expires after its next session`() =
        runTest {
            val d1 = LocalDate.of(2024, 3, 4)
            val bars =
                listOf(
                    dailyBar(d1, 99.0, 100.5, 98.5, 100.0),
                    // Day N+1 never reaches the 100.00 entry → good-for-next-session expiry...
                    dailyBar(d1.plusDays(1), 98.0, 99.5, 97.5, 99.0),
                    // ...so day N+2 touching the level must NOT fill a stale order.
                    dailyBar(d1.plusDays(2), 99.5, 101.0, 99.0, 100.5),
                )
            val strategy = OneShotStrategy()

            engineOver(bars).run<String>(
                BacktestEngine.Request(
                    symbols = listOf(symbol),
                    from = d1,
                    to = d1.plusDays(2),
                    holdOvernight = true,
                    timeframe = Timeframe.DAILY,
                ),
                strategy,
            )

            assertEquals(listOf("t1"), strategy.expired, "entry unfilled on day N+1 must expire that night")
            assertTrue(strategy.fills.isEmpty(), "stale entry must not fill on day N+2")
        }
}
