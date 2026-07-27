package cz.solvina.options.lifecycle

import cz.solvina.options.adapters.inbound.lifecycle.FlagRecoveryService
import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.AccountTradingPort
import cz.solvina.options.domain.features.account.EffectiveAccountService
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.flag.BracketOrderIds
import cz.solvina.options.domain.features.flag.BracketOrderPort
import cz.solvina.options.domain.features.flag.FlagExecutionService
import cz.solvina.options.domain.features.flag.FlagPage
import cz.solvina.options.domain.features.flag.FlagPort
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.order.BrokerOrderUpdate
import cz.solvina.options.domain.features.order.OrderLifecyclePort
import cz.solvina.options.domain.models.ConnectionStatus
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
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

class FlagRecoveryServiceTest {
    private val fixedClock = Clock.fixed(Instant.parse("2025-06-05T14:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `all position fetches throw leaves rows untouched`() =
        runTest {
            val flagPort = CapturingFlagPort(listOf(pendingPosition()))
            val positionsPort =
                object : PositionsPort {
                    override suspend fun getPositions() = error("position feed unavailable")
                }
            val service = service(flagPort = flagPort, positionsPort = positionsPort)

            service.recover()

            assertEquals(0, flagPort.updated.size)
            assertEquals(FlagStatus.PENDING, flagPort.rows.single().status)
        }

    @Test
    fun `successful empty snapshot marks vanished pending entry as entry timeout`() =
        runTest {
            val flagPort = CapturingFlagPort(listOf(pendingPosition()))
            val service =
                service(
                    flagPort = flagPort,
                    positionsPort =
                        object : PositionsPort {
                            override suspend fun getPositions() = emptyList<AccountPosition>()
                        },
                )

            service.recover()

            assertEquals(FlagStatus.ENTRY_TIMEOUT, flagPort.rows.single().status)
            assertEquals("recovery_entry_not_filled", flagPort.rows.single().closeReason)
        }

    @Test
    fun `successful empty snapshot marks vanished open flag as closed external`() =
        runTest {
            val flagPort = CapturingFlagPort(listOf(openPosition()))
            val service =
                service(
                    flagPort = flagPort,
                    positionsPort =
                        object : PositionsPort {
                            override suspend fun getPositions() = emptyList<AccountPosition>()
                        },
                )

            service.recover()

            assertEquals(FlagStatus.CLOSED_EXTERNAL, flagPort.rows.single().status)
            assertEquals("recovery_exit_filled_externally", flagPort.rows.single().closeReason)
        }

    private fun service(
        flagPort: CapturingFlagPort,
        positionsPort: PositionsPort,
    ): FlagRecoveryService {
        val accountTradingPort = mockk<AccountTradingPort>()
        coEvery { accountTradingPort.getOpenOrders() } returns emptyList()
        return FlagRecoveryService(
            flagPort = flagPort,
            bracketOrderPort = NoopBracketOrderPort,
            flagExecutionService =
                FlagExecutionService(
                    bracketOrderPort = NoopBracketOrderPort,
                    orderLifecyclePort = NoopOrderLifecyclePort,
                    flagPort = flagPort,
                    clock = fixedClock,
                    scope = CoroutineScope(StandardTestDispatcher()),
                    effectiveAccount =
                        EffectiveAccountService(
                            object : AccountPort {
                                override val accountDetail = MutableStateFlow(null)
                            },
                            null,
                        ),
                ),
            accountTradingPort = accountTradingPort,
            positionsPort = positionsPort,
            alertPort = CapturingAlertPort,
            connectionStatusPort = ConnectedStatusPort,
            clock = fixedClock,
        )
    }

    private fun pendingPosition() =
        basePosition().copy(
            status = FlagStatus.PENDING,
            entryOrderId = 10,
            stopLossOrderId = 11,
            profitTargetOrderId = 11,
        )

    private fun openPosition() =
        basePosition().copy(
            status = FlagStatus.OPEN,
            entryOrderId = 20,
            stopLossOrderId = 21,
            profitTargetOrderId = 21,
        )

    private fun basePosition() =
        FlagPosition(
            id = UUID.randomUUID(),
            symbol = Symbol("AAPL"),
            status = FlagStatus.PENDING,
            entryOrderId = 10,
            stopLossOrderId = 11,
            profitTargetOrderId = 11,
            entryPrice = BigDecimal("10.00"),
            stopLossPrice = BigDecimal("9.00"),
            profitTargetPrice = BigDecimal("12.00"),
            trailAmount = BigDecimal("2.00"),
            shares = 100,
            riskAmount = BigDecimal("100.00"),
            flagpoleHeight = null,
            flagRetracement = null,
            resistanceAtEntry = null,
            patternStartedAt = null,
            openedAt = Instant.parse("2025-06-05T13:00:00Z"),
        )

    private class CapturingFlagPort(
        initialRows: List<FlagPosition>,
    ) : FlagPort {
        val rows = initialRows.toMutableList()
        val updated = mutableListOf<FlagPosition>()

        override suspend fun save(position: FlagPosition): FlagPosition = position.also { rows += it }

        override suspend fun update(position: FlagPosition): FlagPosition {
            rows.replaceAll { if (it.id == position.id) position else it }
            updated += position
            return position
        }

        override suspend fun findById(id: UUID): FlagPosition? = rows.firstOrNull { it.id == id }

        override suspend fun findOpen(): List<FlagPosition> = rows.filter { it.status == FlagStatus.OPEN }

        override suspend fun findAll(): List<FlagPosition> = rows.toList()

        override suspend fun findPage(
            status: FlagStatus?,
            page: Int,
            size: Int,
            sort: String,
            sortDir: String,
        ) = FlagPage(emptyList(), 0, 0, page, size)

        override suspend fun countByStatus(status: FlagStatus): Long = rows.count { it.status == status }.toLong()

        override suspend fun findByStatuses(statuses: Set<FlagStatus>): List<FlagPosition> = rows.filter { it.status in statuses }
    }

    private object NoopBracketOrderPort : BracketOrderPort {
        override fun reserveBracketOrderIds() = BracketOrderIds(1, 2, 2)

        override suspend fun cancelOrder(orderId: Int) {}

        override suspend fun submitTrailingStopSell(
            symbol: Symbol,
            shares: Int,
            initialStop: BigDecimal,
            trailAmount: BigDecimal,
        ) = 99

        override suspend fun submitMarketSell(
            orderId: Int,
            symbol: Symbol,
            shares: Int,
        ) = orderId
    }

    private object NoopOrderLifecyclePort : OrderLifecyclePort {
        override val updates = emptyFlow<BrokerOrderUpdate>()

        override fun current(orderId: Int): BrokerOrderUpdate? = null
    }

    private object CapturingAlertPort : AlertPort {
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
}
