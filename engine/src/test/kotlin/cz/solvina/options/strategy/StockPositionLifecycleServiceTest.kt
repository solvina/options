package cz.solvina.options.strategy

import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.order.BrokerOrderUpdate
import cz.solvina.options.domain.features.order.OrderLifecyclePort
import cz.solvina.options.domain.features.strategy.live.StockPosition
import cz.solvina.options.domain.features.strategy.live.StockPositionLifecycleService
import cz.solvina.options.domain.features.strategy.live.StockPositionPort
import cz.solvina.options.domain.features.strategy.live.StockPositionStatus
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StockPositionLifecycleServiceTest {
    private val at = Instant.parse("2026-08-07T14:00:00Z")

    @Test
    fun `entry fill opens the position and records the fill price`() =
        runTest {
            val positions = FakePositions(pending())
            Harness(positions, testScheduler).emit(update(ENTRY_ID, "Filled", BigDecimal("99.50")))

            val row = positions.rows.single()
            assertEquals(StockPositionStatus.OPEN, row.status)
            assertEquals(BigDecimal("99.50"), row.actualEntryPrice)
            assertEquals(at, row.openedAt)
            // Signal 100.00, filled 99.50: a limit that filled better than the signal.
            assertEquals(BigDecimal("-0.50"), row.entrySlippage)
        }

    @Test
    fun `cancelled entry is recorded as unfilled, not as a loss`() =
        runTest {
            val positions = FakePositions(pending())
            Harness(positions, testScheduler).emit(update(ENTRY_ID, "Cancelled", null))

            val row = positions.rows.single()
            assertEquals(StockPositionStatus.ENTRY_UNFILLED, row.status)
            assertEquals(at, row.closedAt)
            assertNull(row.realizedPnl)
        }

    @Test
    fun `stop fill closes at the fill price with realized loss`() =
        runTest {
            val positions = FakePositions(open())
            Harness(positions, testScheduler).emit(update(STOP_ID, "Filled", BigDecimal("94.00")))

            val row = positions.rows.single()
            assertEquals(StockPositionStatus.CLOSED_STOP, row.status)
            assertEquals(BigDecimal("94.00"), row.closePrice)
            // (94.00 - 99.50) x 10 shares
            assertEquals(BigDecimal("-55.00"), row.realizedPnl)
            assertEquals("stop_hit", row.closeReason)
        }

    @Test
    fun `target fill closes at the fill price with realized profit`() =
        runTest {
            val positions = FakePositions(open())
            Harness(positions, testScheduler).emit(update(TARGET_ID, "Filled", BigDecimal("110.00")))

            val row = positions.rows.single()
            assertEquals(StockPositionStatus.CLOSED_TARGET, row.status)
            assertEquals(BigDecimal("105.00"), row.realizedPnl)
            assertEquals("target_hit", row.closeReason)
        }

    @Test
    fun `a fill with no reported price falls back to the order's own trigger and says so`() =
        runTest {
            val positions = FakePositions(open())
            Harness(positions, testScheduler).emit(update(STOP_ID, "Filled", null))

            val row = positions.rows.single()
            assertEquals(BigDecimal("95.00"), row.closePrice) // the stop price
            assertTrue(row.closeReason!!.endsWith("_estimated"))
        }

    @Test
    fun `an already terminal position ignores late updates`() =
        runTest {
            val closed = open().copy(status = StockPositionStatus.CLOSED_TARGET, realizedPnl = BigDecimal("105.00"))
            val positions = FakePositions(closed)
            Harness(positions, testScheduler).emit(update(STOP_ID, "Filled", BigDecimal("94.00")))

            assertEquals(StockPositionStatus.CLOSED_TARGET, positions.rows.single().status)
            assertEquals(BigDecimal("105.00"), positions.rows.single().realizedPnl)
        }

    @Test
    fun `an update for an unregistered order is ignored`() =
        runTest {
            val positions = FakePositions(pending())
            Harness(positions, testScheduler, register = false)
                .emit(update(ENTRY_ID, "Filled", BigDecimal("99.50")))

            assertEquals(StockPositionStatus.PENDING, positions.rows.single().status)
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /**
     * Drives the service the way the broker does — through the update flow — rather than calling a
     * handler directly, so registration and collection are exercised too.
     */
    private class Harness(
        positions: FakePositions,
        private val scheduler: TestCoroutineScheduler,
        register: Boolean = true,
    ) {
        private val feed = FakeLifecyclePort()
        private val service = StockPositionLifecycleService(positions, feed, CoroutineScope(StandardTestDispatcher(scheduler)))

        init {
            if (register) service.register(positions.rows.single())
        }

        /** Emits, then drains the scheduler so the handler's own suspensions have all completed. */
        suspend fun emit(update: BrokerOrderUpdate) {
            feed.updates.emit(update)
            scheduler.advanceUntilIdle()
        }
    }

    private fun update(
        orderId: Int,
        status: String,
        fill: BigDecimal?,
    ) = BrokerOrderUpdate(
        orderId = orderId,
        status = status,
        filled = if (status == "Filled") BigDecimal.TEN else BigDecimal.ZERO,
        remaining = BigDecimal.ZERO,
        averageFillPrice = fill,
        receivedAt = at,
    )

    private fun pending() =
        StockPosition(
            id = UUID.randomUUID(),
            strategyId = "mfi_oversold",
            assignmentId = UUID.randomUUID(),
            paramsJson = "{}",
            symbol = Symbol("AAPL"),
            timeframe = Timeframe.DAILY,
            status = StockPositionStatus.PENDING,
            entryOrderId = ENTRY_ID,
            stopOrderId = STOP_ID,
            targetOrderId = TARGET_ID,
            signalPrice = BigDecimal("100.00"),
            limitPrice = BigDecimal("100.00"),
            stopPrice = BigDecimal("95.00"),
            targetPrice = BigDecimal("110.00"),
            shares = 10,
            signalledAt = at,
        )

    private fun open() =
        pending().copy(
            status = StockPositionStatus.OPEN,
            actualEntryPrice = BigDecimal("99.50"),
            openedAt = at,
        )

    private class FakePositions(
        initial: StockPosition,
    ) : StockPositionPort {
        val rows = mutableListOf(initial)

        override suspend fun save(position: StockPosition): StockPosition {
            rows.replaceAll { if (it.id == position.id) position else it }
            if (rows.none { it.id == position.id }) rows += position
            return position
        }

        override suspend fun findById(id: UUID): StockPosition? = rows.firstOrNull { it.id == id }

        override suspend fun findLive(): List<StockPosition> = rows.filter { it.status.isLive }

        override suspend fun findAll(): List<StockPosition> = rows.toList()

        override suspend fun findLiveFor(
            strategyId: String,
            symbol: Symbol,
        ): StockPosition? = rows.firstOrNull { it.strategyId == strategyId && it.symbol == symbol && it.status.isLive }
    }

    private class FakeLifecyclePort : OrderLifecyclePort {
        override val updates = MutableSharedFlow<BrokerOrderUpdate>()

        /** Nothing to replay: these tests emit every update explicitly. */
        override fun current(orderId: Int): BrokerOrderUpdate? = null
    }

    private companion object {
        const val ENTRY_ID = 10
        const val STOP_ID = 11
        const val TARGET_ID = 12
    }
}
