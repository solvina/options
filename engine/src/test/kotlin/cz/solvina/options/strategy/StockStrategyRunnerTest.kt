package cz.solvina.options.strategy

import com.fasterxml.jackson.databind.ObjectMapper
import cz.solvina.options.domain.features.account.AccountDetail
import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.account.EffectiveAccountService
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.Candle
import cz.solvina.options.domain.features.bars.SeriesSummary
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.order.BrokerOrderUpdate
import cz.solvina.options.domain.features.order.OrderLifecyclePort
import cz.solvina.options.domain.features.strategy.MfiOversoldStrategy
import cz.solvina.options.domain.features.strategy.StockStrategy
import cz.solvina.options.domain.features.strategy.StrategyRegistry
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignment
import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignmentPort
import cz.solvina.options.domain.features.strategy.live.StockOrderIds
import cz.solvina.options.domain.features.strategy.live.StockOrderPort
import cz.solvina.options.domain.features.strategy.live.StockPosition
import cz.solvina.options.domain.features.strategy.live.StockPositionLifecycleService
import cz.solvina.options.domain.features.strategy.live.StockPositionPort
import cz.solvina.options.domain.features.strategy.live.StockStrategyConfig
import cz.solvina.options.domain.features.strategy.live.StockStrategyRunner
import cz.solvina.options.domain.features.strategy.tuning.StrategyDefaultParamsPort
import cz.solvina.options.domain.features.strategy.tuning.StrategyParamsResolver
import cz.solvina.options.domain.features.strategy.tuning.StrategySymbolParamsPort
import cz.solvina.options.domain.features.strategy.tuning.TunableStrategyRegistry
import cz.solvina.options.domain.features.universe.DaySession
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.MarketCalendarPort
import cz.solvina.options.domain.features.universe.MarketSchedule
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two host-level rules that decide whether a signal may become an order at all: the bar it is
 * computed from must be current, and the limit it rests at must be the one the strategy asked for.
 */
class StockStrategyRunnerTest {
    private val today = Instant.parse("2026-08-07T13:00:00Z") // Friday, during the US session
    private val clock = Clock.fixed(today, ZoneOffset.UTC)

    @Test
    fun `a stale newest bar blocks the entry even with a full warmup window`() =
        runTest {
            // Plenty of bars, but the series stopped three weeks ago — the exact shape of the paper
            // host on 2026-08-07, whose newest daily bars were from 2026-07-21.
            val orders = FakeOrders()
            val runner = runner(bars = washoutSeries(lastBarDate = LocalDate.of(2026, 7, 21)), orders = orders)

            runner.runOnce()

            assertTrue(orders.submitted.isEmpty(), "a signal from a 3-week-old bar must not reach the broker")
        }

    @Test
    fun `a bar from the previous session is fresh enough to trade`() =
        runTest {
            val orders = FakeOrders()
            val runner = runner(bars = washoutSeries(lastBarDate = LocalDate.of(2026, 8, 6)), orders = orders)

            runner.runOnce()

            assertEquals(1, orders.submitted.size)
        }

    @Test
    fun `the entry limit rests exactly where the strategy asked, with no host-added tolerance`() =
        runTest {
            val orders = FakeOrders()
            val positions = FakePositions()
            val bars = washoutSeries(lastBarDate = LocalDate.of(2026, 8, 6))
            val runner = runner(bars = bars, orders = orders, positions = positions)

            runner.runOnce()

            // MfiOversoldStrategy's own limitToleranceAtr defaults to 0, so the limit is the signal
            // close. Any drift here means the host is trading a rule the backtest never measured.
            val row = positions.rows.single()
            assertEquals(BigDecimal.valueOf(bars.last().close).setScale(4, java.math.RoundingMode.HALF_UP), row.limitPrice)
            assertEquals(row.limitPrice, orders.submitted.single())
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /**
     * A series that ends in a money-flow washout: a long calm uptrend, then a run of heavy-volume
     * down bars that drives MFI(14) under the threshold on the final bar.
     */
    private fun washoutSeries(lastBarDate: LocalDate): List<Candle> {
        val bars = mutableListOf<Candle>()
        var price = 100.0
        val start = lastBarDate.minusDays(59)
        var date = start
        while (!date.isAfter(lastBarDate)) {
            val fallingTail = date.isAfter(lastBarDate.minusDays(20))
            if (fallingTail) price -= 1.5 else price += 0.2
            bars +=
                Candle(
                    time = date.atTime(LocalTime.of(16, 0)).atZone(ZoneId.of("America/New_York")).toInstant(),
                    open = price + 0.5,
                    high = price + 1.0,
                    low = price - 1.0,
                    close = price,
                    volume = if (fallingTail) 5_000_000L else 1_000_000L,
                )
            date = date.plusDays(1)
        }
        return bars
    }

    private fun runner(
        bars: List<Candle>,
        orders: FakeOrders = FakeOrders(),
        positions: FakePositions = FakePositions(),
    ): StockStrategyRunner {
        val strategy: StockStrategy = MfiOversoldStrategy()
        val resolver =
            StrategyParamsResolver(
                registry = TunableStrategyRegistry(listOf(strategy)),
                defaults = NoDefaults,
                symbolParams = NoSymbolParams,
            )
        return StockStrategyRunner(
            assignments = FakeAssignments,
            strategies = StrategyRegistry(listOf(strategy)),
            positions = positions,
            orders = orders,
            barStore = FakeBarStore(bars),
            universe = FakeUniverse,
            effectiveAccount = EffectiveAccountService(FakeAccountPort, null),
            config = StockStrategyConfig(enabled = true),
            paramsResolver = resolver,
            mapper = ObjectMapper(),
            lifecycle =
                StockPositionLifecycleService(positions, NoopOrderLifecyclePort, CoroutineScope(StandardTestDispatcher())),
            calendar = WeekdayCalendar,
            clock = clock,
        )
    }

    private object FakeAssignments : StrategyAssignmentPort {
        private val assignment =
            StrategyAssignment(
                id = UUID.randomUUID(),
                strategyId = MfiOversoldStrategy.ID,
                symbol = Symbol("AAPL"),
                timeframe = Timeframe.DAILY,
                enabled = true,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )

        override fun findAll() = listOf(assignment)

        override fun findEnabled() = listOf(assignment)

        override fun findById(id: UUID) = assignment.takeIf { it.id == id }

        override fun save(assignment: StrategyAssignment) = assignment

        override fun delete(id: UUID) = true
    }

    private class FakeBarStore(
        private val bars: List<Candle>,
    ) : BarStorePort {
        override suspend fun writeBar(
            symbol: Symbol,
            bar: Candle,
            timeframe: Timeframe,
        ) = Unit

        override suspend fun writeBars(
            symbol: Symbol,
            bars: List<Candle>,
            timeframe: Timeframe,
        ) = Unit

        override suspend fun readBars(
            symbol: Symbol,
            from: Instant,
            to: Instant,
            timeframe: Timeframe,
        ) = bars.filter { it.time >= from && it.time <= to }

        override suspend fun lastBarTime(
            symbol: Symbol,
            timeframe: Timeframe,
        ) = bars.lastOrNull()?.time

        override suspend fun coverageByDay(
            symbol: Symbol,
            from: LocalDate,
            to: LocalDate,
            timeframe: Timeframe,
        ) = emptyMap<LocalDate, Int>()

        override suspend fun seriesSummary() = emptyList<SeriesSummary>()
    }

    /** No holidays: every weekday is a session. Enough to pin the freshness rule itself. */
    private object WeekdayCalendar : MarketCalendarPort {
        override fun sessionFor(
            symbol: Symbol,
            date: LocalDate,
        ): DaySession =
            if (date.dayOfWeek.value <= 5) {
                DaySession.Open(LocalTime.of(9, 30), LocalTime.of(16, 0))
            } else {
                DaySession.Closed
            }
    }

    private object FakeUniverse : UniversePort {
        override fun getWatchlist() = listOf(Symbol("AAPL"))

        override fun getFlagWatchlist() = emptyList<Symbol>()

        override fun getActiveSymbols() = listOf(Symbol("AAPL"))

        override fun isMarketOpen(symbol: Symbol) = true

        override fun getMarketSchedule(symbol: Symbol) =
            MarketSchedule(ZoneId.of("America/New_York"), LocalTime.of(9, 30), LocalTime.of(16, 0), "US")

        override suspend fun getAll() = emptyList<InstrumentConfig>()

        override suspend fun get(symbol: Symbol): InstrumentConfig? = null

        override suspend fun save(config: InstrumentConfig) = config

        override suspend fun delete(symbol: Symbol) = Unit
    }

    private object FakeAccountPort : AccountPort {
        override val accountDetail =
            MutableStateFlow(
                AccountDetail(
                    totalCapital = Money(BigDecimal("100000")),
                    availableFunds = Money(BigDecimal("100000")),
                    excessLiquidity = Money(BigDecimal("100000")),
                ),
            )
    }

    private class FakePositions : StockPositionPort {
        val rows = mutableListOf<StockPosition>()

        override suspend fun save(position: StockPosition): StockPosition {
            rows.replaceAll { if (it.id == position.id) position else it }
            if (rows.none { it.id == position.id }) rows += position
            return position
        }

        override suspend fun findById(id: UUID) = rows.firstOrNull { it.id == id }

        override suspend fun findLive() = rows.filter { it.status.isLive }

        override suspend fun findAll() = rows.toList()

        override suspend fun findLiveFor(
            strategyId: String,
            symbol: Symbol,
        ) = rows.firstOrNull { it.strategyId == strategyId && it.symbol == symbol && it.status.isLive }
    }

    private class FakeOrders : StockOrderPort {
        val submitted = mutableListOf<BigDecimal>()
        private var nextId = 1

        override fun reserveOrderIds() = StockOrderIds(nextId++, nextId++, nextId++)

        override suspend fun submitLimitEntryWithProtection(
            ids: StockOrderIds,
            symbol: Symbol,
            shares: Int,
            limitPrice: BigDecimal,
            stopPrice: BigDecimal,
            targetPrice: BigDecimal?,
        ): StockOrderIds {
            submitted += limitPrice
            return ids
        }

        override suspend fun submitProtectiveStop(
            symbol: Symbol,
            shares: Int,
            stopPrice: BigDecimal,
        ) = nextId++

        override suspend fun cancelOrder(orderId: Int) = Unit
    }

    private object NoDefaults : StrategyDefaultParamsPort {
        override suspend fun get(strategyId: String): Map<String, Any?>? = null

        override suspend fun upsert(
            strategyId: String,
            params: Map<String, Any?>,
        ) = Unit

        override suspend fun delete(strategyId: String) = Unit
    }

    private object NoSymbolParams : StrategySymbolParamsPort {
        override suspend fun get(
            strategyId: String,
            symbol: String,
            timeframe: String,
        ): Map<String, Any?>? = null

        override suspend fun allForStrategy(strategyId: String) = emptyMap<String, Map<String, Any?>>()

        override suspend fun upsert(
            strategyId: String,
            symbol: String,
            params: Map<String, Any?>,
            timeframe: String,
        ) = Unit

        override suspend fun delete(
            strategyId: String,
            symbol: String,
            timeframe: String,
        ) = Unit
    }

    private object NoopOrderLifecyclePort : OrderLifecyclePort {
        override val updates = emptyFlow<BrokerOrderUpdate>()

        override fun current(orderId: Int): BrokerOrderUpdate? = null
    }
}
