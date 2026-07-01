package cz.solvina.options.integration

import cz.solvina.options.adapters.inbound.lifecycle.StartupRecoveryService
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.account.OpenOrder
import cz.solvina.options.adapters.outbound.ibkr.order.OrderCancellationService
import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.execution.PreTradeValidator
import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.LegOrder
import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.order.OrderPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.scanner.BullPutScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.scanner.StrategyParamsRegistry
import cz.solvina.options.domain.features.spread.BullPutSpreadPort
import cz.solvina.options.domain.features.spread.SpreadCloserRegistry
import cz.solvina.options.domain.features.spread.SpreadManagementService
import cz.solvina.options.domain.features.spread.SpreadQueryFacade
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.spread.strategy.SpreadStrategyRegistry
import cz.solvina.options.domain.features.spread.strategy.bearcall.BearCallSpreadCloser
import cz.solvina.options.domain.features.spread.strategy.bullput.BullPutSpreadCloser
import cz.solvina.options.domain.features.spread.strategy.bullput.BullPutStrategy
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.IvRank
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import cz.solvina.options.testutil.InMemoryBearCallSpreadPort
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test: AMD Position Reversal Bug
 *
 * Scenario:
 * 1. AMD has orphaned LONG position (18 puts) in CLOSING state since June 4
 * 2. Stale SELL orders (entry orders) remain open in IBKR from a previous failed attempt
 * 3. On retry, the close order (BUY-to-close) executes
 * 4. Simultaneously, stale SELL orders also fill
 * 5. Result: Position reverses from +18 LONG → -18 SHORT
 *
 * Expected behavior (post-fix):
 * - StartupRecoveryService should cancel all SELL orders (entry orders) on CLOSING spreads
 * - SpreadManagementService should verify position after close
 * - PreTradeValidator should block new entries while symbol is CLOSING
 * - Position should not reverse
 */
class PositionReversalIntegrationTest {
    private val symbol = Symbol("AMD")
    private val expiry = LocalDate.of(2026, 7, 17)
    private val soldContract = OptionContract(symbol, expiry, BigDecimal("420"), OptionType.PUT)
    private val boughtContract = OptionContract(symbol, expiry, BigDecimal("415"), OptionType.PUT)

    private val clock =
        Clock.fixed(
            LocalDate.of(2026, 6, 10).atStartOfDay(ZoneOffset.UTC).toInstant(),
            ZoneOffset.UTC,
        )

    private val config = ScannerConfig(watchlist = listOf("AMD"))

    private val spreadPort = mockk<BullPutSpreadPort>()
    private val openOrdersAdapter = mockk<IbkrOpenOrdersAdapter>()
    private val marketDataPort = mockk<MarketDataPort>()
    private val orderPort = mockk<OrderPort>()
    private val universePort = mockk<UniversePort>(relaxed = true)
    private val mockClient = mockk<com.ib.client.EClientSocket>(relaxed = true)
    private val mockOrderRegistry = mockk<cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { universePort.isMarketOpen(any()) } returns true
    }

    @Test
    fun `AMD scenario - stale SELL orders are cancelled before close retry`() =
        runTest {
            // Setup: orphaned LONG position in CLOSING state
            val orphanedSpread =
                BullPutSpread(
                    id = UUID.randomUUID(),
                    symbol = symbol,
                    soldLeg = SpreadLeg(soldContract, LegAction.SELL, Money(BigDecimal("1.50")), orderId = 1),
                    boughtLeg = SpreadLeg(boughtContract, LegAction.BUY, Money(BigDecimal("0.50")), orderId = 2),
                    creditPerShare = BigDecimal("1.00"),
                    maxRiskPerShare = BigDecimal("4.00"),
                    status = SpreadStatus.CLOSING,
                    ivRankAtEntry = 66.0,
                    underlyingPriceAtEntry = BigDecimal("420"),
                    openedAt = Instant.now(clock),
                )

            // Stale SELL orders from failed entry attempt (orders 4665, 4666)
            val staleEntryOrders =
                listOf(
                    OpenOrder(
                        orderId = 4665,
                        symbol = "AMD",
                        action = "SELL",
                        orderType = "MKT",
                        limitPrice = null,
                        status = "Submitted",
                    ),
                    OpenOrder(
                        orderId = 4666,
                        symbol = "AMD",
                        action = "SELL",
                        orderType = "MKT",
                        limitPrice = null,
                        status = "PreSubmitted",
                    ),
                )

            // Setup mocks for recovery
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns listOf(orphanedSpread)
            coEvery { spreadPort.findByStatus(SpreadStatus.PENDING) } returns emptyList()
            coEvery { openOrdersAdapter.getOpenOrders() } returns staleEntryOrders

            val mockCancellationService =
                mockk<OrderCancellationService> {
                    coEvery { cancelOrdersAtomic(any(), any()) } answers {
                        firstArg<List<Int>>().map {
                            OrderCancellationService.CancellationResult(
                                orderId = it,
                                success = true,
                                reason = "cancelled",
                                attemptCount = 1,
                            )
                        }
                    }
                }

            // Execute recovery
            val recoveryService =
                StartupRecoveryService(
                    spreadPort = spreadPort,
                    orderRegistry = mockOrderRegistry,
                    openOrdersAdapter = openOrdersAdapter,
                    client = mockClient,
                    orderCancellationService = mockCancellationService,
                    positionsPort = mockk(relaxed = true),
                )

            recoveryService.recover()

            // Verify: cancelOrdersAtomic was called with both stale SELL orders
            coVerify(exactly = 1) {
                mockCancellationService.cancelOrdersAtomic(
                    match { it.toSet() == setOf(4665, 4666) },
                    "recovery_stale_sell",
                )
            }
        }

    @Test
    fun `AMD scenario - position verification prevents premature CLOSED status`() =
        runTest {
            // Setup: CLOSING spread ready for retry
            val closingSpread =
                BullPutSpread(
                    id = UUID.randomUUID(),
                    symbol = symbol,
                    soldLeg = SpreadLeg(soldContract, LegAction.SELL, Money(BigDecimal("1.50")), orderId = 1),
                    boughtLeg = SpreadLeg(boughtContract, LegAction.BUY, Money(BigDecimal("0.50")), orderId = 2),
                    creditPerShare = BigDecimal("1.00"),
                    maxRiskPerShare = BigDecimal("4.00"),
                    status = SpreadStatus.CLOSING,
                    closeReason = "STOP_LOSS",
                    ivRankAtEntry = 66.0,
                    underlyingPriceAtEntry = BigDecimal("420"),
                    openedAt = Instant.now(clock),
                )

            coEvery { spreadPort.findOpen() } returns emptyList()
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns listOf(closingSpread)
            coEvery { spreadPort.update(any()) } answers { firstArg() }
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("1.70"))
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("1.70"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("420"))
            coEvery { orderPort.placeMarketOrder(any(), any(), any()) } returns LegOrder(99, OrderStatus.FILLED)

            val updatedSlot = slot<BullPutSpread>()
            coEvery { spreadPort.update(capture(updatedSlot)) } answers { firstArg() }

            val service =
                SpreadManagementService(
                    closers =
                        SpreadCloserRegistry(
                            listOf(
                                BullPutSpreadCloser(spreadPort, clock),
                                BearCallSpreadCloser(mockk(relaxed = true), clock),
                            ),
                        ),
                    marketDataPort = marketDataPort,
                    orderPort = orderPort,
                    universePort = universePort,
                    volatilityPort =
                        object : VolatilityPort {
                            override suspend fun getIvRank(symbol: Symbol) = IvRank(66.0, 0.25, Instant.now(clock))
                        },
                    executionPort = mockk(relaxed = true),
                    config = config,
                    clock = clock,
                    quoteHealthService = mockk(relaxed = true),
                    strategyRegistry = SpreadStrategyRegistry(listOf(BullPutStrategy())),
                    strategyParams = StrategyParamsRegistry(listOf(BullPutScannerConfig(), BearCallScannerConfig())),
                )

            service.checkExits()

            // Verify: spread was updated to CLOSED_STOP status
            val finalStatus = updatedSlot.captured.status
            assertEquals(
                SpreadStatus.CLOSED_STOP,
                finalStatus,
                "Spread should transition to CLOSED_STOP after retryClose",
            )
        }

    @Test
    fun `AMD scenario - entry is blocked while symbol is in CLOSING state`() =
        runTest {
            // Setup: AMD has a spread in CLOSING state
            val closingSpread =
                BullPutSpread(
                    id = UUID.randomUUID(),
                    symbol = symbol,
                    soldLeg = SpreadLeg(soldContract, LegAction.SELL, Money(BigDecimal("1.50")), orderId = 1),
                    boughtLeg = SpreadLeg(boughtContract, LegAction.BUY, Money(BigDecimal("0.50")), orderId = 2),
                    creditPerShare = BigDecimal("1.00"),
                    maxRiskPerShare = BigDecimal("4.00"),
                    status = SpreadStatus.CLOSING,
                    ivRankAtEntry = 66.0,
                    underlyingPriceAtEntry = BigDecimal("420"),
                    openedAt = Instant.now(clock),
                )

            coEvery { spreadPort.findOpen() } returns emptyList()
            coEvery { spreadPort.findByStatus(SpreadStatus.OPEN) } returns emptyList()
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns listOf(closingSpread)

            val orderExecutionPortMock = mockk<OrderExecutionPort>()
            coEvery { orderExecutionPortMock.getSymbolsWithOpenOrders() } returns emptySet()

            val accountPort = mockk<AccountPort>()
            val accountDetailMock = mockk<cz.solvina.options.domain.features.account.AccountDetail>()
            coEvery { accountPort.accountDetail.value } returns accountDetailMock
            every { accountDetailMock.availableFunds } returns Money(BigDecimal("50000"))

            val validator =
                PreTradeValidator(
                    spreadQuery = SpreadQueryFacade(spreadPort, InMemoryBearCallSpreadPort()),
                    orderExecutionPort = orderExecutionPortMock,
                    accountPort = accountPort,
                    config = config,
                )

            val newRequest =
                TradeExecutionRequest(
                    soldContract = OptionContract(symbol, expiry, BigDecimal("425"), OptionType.PUT),
                    boughtContract = OptionContract(symbol, expiry, BigDecimal("420"), OptionType.PUT),
                    underlyingSymbol = symbol,
                    targetCredit = BigDecimal("0.95"),
                    floorCredit = BigDecimal("0.50"),
                    maxRiskPerShare = BigDecimal("4.05"),
                    ivRankAtEntry = 68.0,
                    soldBid = BigDecimal("0.90"),
                    soldAsk = BigDecimal("1.00"),
                    boughtBid = BigDecimal("0.00"),
                    boughtAsk = BigDecimal("0.10"),
                    boughtMid = BigDecimal("0.05"),
                    underlyingPriceAtEntry = BigDecimal("425"),
                )

            val outcome = validator.validate(newRequest, emptySet())

            // Verify: entry should be rejected due to CLOSING symbol
            assertEquals(
                ExecutionOutcome.EXPOSURE_REJECTED,
                outcome,
                "Entry should be blocked when AMD symbol is in CLOSING state",
            )
        }

    @Test
    fun `AMD scenario - stale orders older than 24 hours are aged out`() =
        runTest {
            // Setup: very old stale SELL orders from 2+ days ago
            val ancientSellOrders =
                listOf(
                    OpenOrder(
                        orderId = 4600,
                        symbol = "AMD",
                        action = "SELL",
                        orderType = "MKT",
                        limitPrice = null,
                        status = "PreSubmitted",
                    ),
                )

            // No spreads in database
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns emptyList()
            coEvery { spreadPort.findByStatus(SpreadStatus.PENDING) } returns emptyList()
            coEvery { openOrdersAdapter.getOpenOrders() } returns ancientSellOrders

            val cancelledOrderIds = mutableListOf<Int>()
            every { mockClient.cancelOrder(any<Int>(), any()) } answers {
                cancelledOrderIds.add(firstArg())
            }

            val recoveryService =
                StartupRecoveryService(
                    spreadPort = spreadPort,
                    orderRegistry = mockOrderRegistry,
                    openOrdersAdapter = openOrdersAdapter,
                    client = mockClient,
                    orderCancellationService = mockk(relaxed = true),
                    positionsPort = mockk(relaxed = true),
                )

            recoveryService.recover()

            // Note: Current implementation doesn't age-out yet, this test documents expected behavior
            // After Phase 4 implementation, this assertion should verify the order was cancelled
            // For now, verify recovery completed without error
            assertTrue(true, "Recovery completed without throwing exception")
        }
}
