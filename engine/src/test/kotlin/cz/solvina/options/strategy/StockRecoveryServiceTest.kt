package cz.solvina.options.strategy

import cz.solvina.options.adapters.inbound.lifecycle.StockRecoveryService
import cz.solvina.options.domain.features.account.AccountOpenOrder
import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.AccountTradingPort
import cz.solvina.options.domain.features.account.BrokerPositionsSnapshotService
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.order.BrokerOrderUpdate
import cz.solvina.options.domain.features.order.OrderLifecyclePort
import cz.solvina.options.domain.features.strategy.live.StockOrderIds
import cz.solvina.options.domain.features.strategy.live.StockOrderPort
import cz.solvina.options.domain.features.strategy.live.StockPosition
import cz.solvina.options.domain.features.strategy.live.StockPositionLifecycleService
import cz.solvina.options.domain.features.strategy.live.StockPositionPort
import cz.solvina.options.domain.features.strategy.live.StockPositionStatus
import cz.solvina.options.domain.models.ConnectionStatus
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StockRecoveryServiceTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-08-07T14:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `an unavailable position feed leaves rows untouched`() =
        runTest {
            val positions = FakePositions(pending())
            val service =
                service(
                    positions,
                    positionsPort =
                        object : PositionsPort {
                            override suspend fun getPositions() = error("position feed unavailable")

                            override suspend fun awaitInitialSnapshot(timeout: kotlin.time.Duration) = false
                        },
                )

            service.recover()

            assertEquals(StockPositionStatus.PENDING, positions.rows.single().status)
        }

    @Test
    fun `a pending row whose entry order vanished without shares is marked unfilled`() =
        runTest {
            val positions = FakePositions(pending())
            val service = service(positions)

            service.recover()

            val row = positions.rows.single()
            assertEquals(StockPositionStatus.ENTRY_UNFILLED, row.status)
            assertEquals("recovery_entry_not_filled", row.closeReason)
        }

    @Test
    fun `an open row whose shares are gone is closed administratively, never sold`() =
        runTest {
            val positions = FakePositions(open())
            val orders = FakeOrders()
            val service = service(positions, orders = orders)

            service.recover()

            val row = positions.rows.single()
            assertEquals(StockPositionStatus.CLOSED_EXTERNAL, row.status)
            assertEquals("recovery_exit_filled_externally", row.closeReason)
            assertTrue(orders.protectiveStops.isEmpty())
            assertNull(row.realizedPnl) // the exit price is genuinely unknown; nothing is invented
        }

    @Test
    fun `a working entry order is only re-registered, not resolved`() =
        runTest {
            val positions = FakePositions(pending())
            val service = service(positions, openOrderIds = listOf(ENTRY_ID))

            service.recover()

            assertEquals(StockPositionStatus.PENDING, positions.rows.single().status)
        }

    @Test
    fun `a working protective order on a pending row means the entry filled while unwatched`() =
        runTest {
            val positions = FakePositions(pending())
            val service = service(positions, openOrderIds = listOf(STOP_ID))

            service.recover()

            val row = positions.rows.single()
            assertEquals(StockPositionStatus.OPEN, row.status)
            // The fill price is unknown; inventing one would corrupt the slippage measurement.
            assertNull(row.actualEntryPrice)
        }

    @Test
    fun `held shares with no protective order get a new stop`() =
        runTest {
            val positions = FakePositions(open())
            val orders = FakeOrders()
            val service = service(positions, orders = orders, held = 10)

            service.recover()

            assertEquals(listOf(Symbol("AAPL") to BigDecimal("95.00")), orders.protectiveStops)
            val row = positions.rows.single()
            assertEquals(StockPositionStatus.OPEN, row.status)
            assertEquals(FakeOrders.NEW_STOP_ID, row.stopOrderId)
        }

    @Test
    fun `a stop that has not been acknowledged yet is not submitted again`() =
        runTest {
            val positions = FakePositions(open())
            val orders = FakeOrders()
            val service = service(positions, orders = orders, held = 10)

            service.recover()
            // Second pass, still inside the acknowledgement grace and the stop has not appeared in
            // open orders. Without the guard this is the 32-stops-in-72-minutes incident.
            service.recover()

            assertEquals(1, orders.protectiveStops.size)
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private fun service(
        positions: FakePositions,
        orders: FakeOrders = FakeOrders(),
        openOrderIds: List<Int> = emptyList(),
        held: Int = 0,
        positionsPort: PositionsPort? = null,
    ): StockRecoveryService {
        val accountTradingPort = mockk<AccountTradingPort>()
        coEvery { accountTradingPort.getOpenOrders() } returns openOrderIds.map { openOrder(it) }
        val feed =
            positionsPort ?: object : PositionsPort {
                override suspend fun getPositions() = if (held > 0) listOf(stockPosition(held)) else emptyList()
            }
        return StockRecoveryService(
            positions = positions,
            orders = orders,
            lifecycle =
                StockPositionLifecycleService(positions, NoopOrderLifecyclePort, CoroutineScope(StandardTestDispatcher())),
            accountTradingPort = accountTradingPort,
            positionsSnapshot = BrokerPositionsSnapshotService(feed),
            alertPort = NoopAlertPort,
            connectionStatusPort = ConnectedStatusPort,
            clock = fixedClock,
        )
    }

    private fun openOrder(id: Int) =
        AccountOpenOrder(
            orderId = id,
            symbol = "AAPL",
            action = "BUY",
            orderType = "LMT",
            status = "Submitted",
            limitPrice = BigDecimal("100.00"),
            clientId = 1,
            cancellable = true,
            cancelBlockedReason = null,
        )

    private fun stockPosition(shares: Int) =
        AccountPosition(
            account = "DU1",
            symbol = "AAPL",
            secType = "STK",
            currency = "USD",
            expiry = null,
            strike = null,
            optionRight = null,
            quantity = BigDecimal(shares),
            marketPrice = 100.0,
            marketValue = 100.0 * shares,
            avgCost = BigDecimal("99.50"),
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
            signalledAt = Instant.parse("2026-08-06T14:00:00Z"),
        )

    private fun open() =
        pending().copy(
            status = StockPositionStatus.OPEN,
            actualEntryPrice = BigDecimal("99.50"),
            openedAt = Instant.parse("2026-08-06T14:05:00Z"),
        )

    private class FakePositions(
        initial: StockPosition,
    ) : StockPositionPort {
        val rows = mutableListOf(initial)

        override suspend fun save(position: StockPosition): StockPosition {
            rows.replaceAll { if (it.id == position.id) position else it }
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

    private class FakeOrders : StockOrderPort {
        val protectiveStops = mutableListOf<Pair<Symbol, BigDecimal>>()

        override fun reserveOrderIds() = StockOrderIds(1, 2, 3)

        override suspend fun submitLimitEntryWithProtection(
            ids: StockOrderIds,
            symbol: Symbol,
            shares: Int,
            limitPrice: BigDecimal,
            stopPrice: BigDecimal,
            targetPrice: BigDecimal?,
        ) = ids

        override suspend fun submitProtectiveStop(
            symbol: Symbol,
            shares: Int,
            stopPrice: BigDecimal,
        ): Int {
            protectiveStops += symbol to stopPrice
            return NEW_STOP_ID
        }

        override suspend fun cancelOrder(orderId: Int) {}

        companion object {
            const val NEW_STOP_ID = 99
        }
    }

    private object NoopOrderLifecyclePort : OrderLifecyclePort {
        override val updates = emptyFlow<BrokerOrderUpdate>()

        override fun current(orderId: Int): BrokerOrderUpdate? = null
    }

    private object NoopAlertPort : AlertPort {
        override suspend fun send(
            level: AlertLevel,
            title: String,
            body: String,
        ) {}
    }

    private object ConnectedStatusPort : ConnectionStatusPort {
        override fun isConnected() = true

        override fun getConnectionStatus() =
            ConnectionStatus(
                connected = true,
                autoReconnectEnabled = true,
                autoReconnectThreadActive = true,
                connectionInitialized = true,
            )
    }

    private companion object {
        const val ENTRY_ID = 10
        const val STOP_ID = 11
        const val TARGET_ID = 12
    }
}
