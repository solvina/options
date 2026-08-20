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
            // NOT the freshly submitted id: unacknowledged is not the same as working, and writing it
            // over the stored one is what left AEP unable to recognise its own live stop (2026-08-20).
            assertEquals(STOP_ID, row.stopOrderId)
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

    // ── AEP, 2026-08-20: 55 stops overnight against a stop that was working the whole time ──

    @Test
    fun `a working stop at the broker is adopted instead of submitting another`() =
        runTest {
            val positions = FakePositions(open().copy(stopOrderId = 999, targetOrderId = null))
            val orders = FakeOrders()
            val service =
                service(positions, orders = orders, held = 10, openOrders = listOf(listOf(stopOrder(71616))))

            service.recover()

            assertTrue(orders.protectiveStops.isEmpty(), "a working stop needs no second one")
            assertEquals(71616, positions.rows.single().stopOrderId)
        }

    @Test
    fun `an acknowledged stop is adopted on the pass that sees it`() =
        runTest {
            val positions = FakePositions(open())
            val orders = FakeOrders()
            val service =
                service(
                    positions,
                    orders = orders,
                    held = 10,
                    openOrders = listOf(emptyList(), listOf(stopOrder(FakeOrders.NEW_STOP_ID))),
                )

            service.recover()
            assertEquals(STOP_ID, positions.rows.single().stopOrderId)

            service.recover()
            assertEquals(1, orders.protectiveStops.size, "the acknowledged stop must not be duplicated")
            assertEquals(FakeOrders.NEW_STOP_ID, positions.rows.single().stopOrderId)
        }

    @Test
    fun `submissions stop at the attempt ceiling instead of looping forever`() =
        runTest {
            val positions = FakePositions(open())
            val orders = FakeOrders()
            val service =
                service(
                    positions,
                    orders = orders,
                    held = 10,
                    // Each read advances an hour, so every pass clears the 10-minute ack grace.
                    clock = SteppingClock(Instant.parse("2026-08-07T14:00:00Z"), java.time.Duration.ofHours(1)),
                )

            repeat(20) { service.recover() }

            assertTrue(
                orders.protectiveStops.size <= 5,
                "expected the ceiling to hold, got ${orders.protectiveStops.size} stops",
            )
        }

    @Test
    fun `a second working stop is never joined by a third`() =
        runTest {
            val positions = FakePositions(open().copy(stopOrderId = 999, targetOrderId = null))
            val orders = FakeOrders()
            val alerts = RecordingAlertPort()
            val service =
                service(
                    positions,
                    orders = orders,
                    held = 10,
                    openOrders = listOf(listOf(stopOrder(71616), stopOrder(93452))),
                    alertPort = alerts,
                )

            service.recover()

            assertTrue(orders.protectiveStops.isEmpty(), "duplicate stops would sell twice the position")
            assertEquals(1, alerts.sent.count { it.first == AlertLevel.CRITICAL })
        }

    @Test
    fun `the unprotected alert is raised once, not every pass`() =
        runTest {
            val positions = FakePositions(open())
            val alerts = RecordingAlertPort()
            val service =
                service(
                    positions,
                    held = 10,
                    clock = SteppingClock(Instant.parse("2026-08-07T14:00:00Z"), java.time.Duration.ofHours(1)),
                    alertPort = alerts,
                )

            repeat(20) { service.recover() }

            assertEquals(1, alerts.sent.count { it.first == AlertLevel.CRITICAL })
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private fun service(
        positions: FakePositions,
        orders: FakeOrders = FakeOrders(),
        openOrderIds: List<Int> = emptyList(),
        held: Int = 0,
        positionsPort: PositionsPort? = null,
        clock: Clock = fixedClock,
        /** Full order rows when the test needs side/type, not just presence. */
        openOrders: List<List<AccountOpenOrder>>? = null,
        alertPort: AlertPort = NoopAlertPort,
    ): StockRecoveryService {
        val accountTradingPort = mockk<AccountTradingPort>()
        if (openOrders != null) {
            // One element per recover() pass, the last repeating — lets a test show a stop being
            // acknowledged on a later pass than the one that submitted it.
            coEvery { accountTradingPort.getOpenOrders() } returnsMany openOrders andThen openOrders.last()
        } else {
            coEvery { accountTradingPort.getOpenOrders() } returns openOrderIds.map { openOrder(it) }
        }
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
            alertPort = alertPort,
            connectionStatusPort = ConnectedStatusPort,
            clock = clock,
        )
    }

    /** A working protective sell-stop, the shape [StockRecoveryService] adopts rather than duplicates. */
    private fun stopOrder(id: Int) = openOrder(id).copy(action = "SELL", orderType = "STP", limitPrice = BigDecimal.ZERO)

    /** Advances on every read, so the acknowledgement grace can actually elapse between passes. */
    private class SteppingClock(
        private var now: Instant,
        private val step: java.time.Duration,
    ) : Clock() {
        override fun instant(): Instant {
            now = now.plus(step)
            return now
        }

        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this
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

    private class RecordingAlertPort : AlertPort {
        val sent = mutableListOf<Triple<AlertLevel, String, String>>()

        override suspend fun send(
            level: AlertLevel,
            title: String,
            body: String,
        ) {
            sent += Triple(level, title, body)
        }
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
