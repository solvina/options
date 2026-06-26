package cz.solvina.options.spread

import cz.solvina.options.domain.features.execution.TradeExecutionPort
import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.execution.model.TradeExecutionResult
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.LegOrder
import cz.solvina.options.domain.features.order.OrderPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.spread.SpreadManagementService
import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.features.spread.strategy.SpreadStrategy
import cz.solvina.options.domain.features.spread.strategy.SpreadStrategyRegistry
import cz.solvina.options.domain.features.spread.strategy.StrategyExit
import cz.solvina.options.domain.features.spread.strategy.bullput.BullPutStrategy
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.IvRank
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Trading rules under test (bull put spread exits):
 *
 *   Take-profit  — close when spread value ≤ credit × (1 − takeProfitPercent).
 *                  Default: close at 50 % profit → threshold = credit × 0.50.
 *   Stop-loss    — close when spread value ≥ credit × (1 + stopLossPercent).
 *                  Default: close at 150 % of credit → threshold = credit × 1.50.
 *                  Side-effect: blocks the symbol from re-entry for 24 h.
 *   Time exit    — close when DTE ≤ timeProfitDte (default 14 days).
 *   CLOSING retry— a stuck CLOSING spread is re-evaluated against current market
 *                  prices so a recovered SL can be relabeled CLOSED_PROFIT.
 */
class SpreadManagementServiceTest {
    // -------------------------------------------------------------------------
    // Shared fixtures
    // -------------------------------------------------------------------------

    private val symbol = Symbol("SPY")
    private val expiry = LocalDate.of(2025, 3, 21)
    private val soldContract = OptionContract(symbol, expiry, BigDecimal("480"), OptionType.PUT)
    private val boughtContract = OptionContract(symbol, expiry, BigDecimal("475"), OptionType.PUT)

    // Fixed to 80 DTE — well above the timeProfitDte threshold (14) so time-exit never fires
    // unless we deliberately move the clock forward.
    private val clockAtEntry =
        Clock.fixed(
            LocalDate.of(2025, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
            java.time.ZoneOffset.UTC,
        )

    private val config = ScannerConfig(watchlist = listOf("SPY"))

    // Real registry with the bull put strategy — exercises the actual seam dispatch (bull put adds
    // no strategy-specific exit, so generic TP/SL/DTE behaviour is unchanged).
    private val strategyRegistry = SpreadStrategyRegistry(listOf(BullPutStrategy()))

    // Shared universe mock — configured in setUp() so each test can override if needed.
    private val universePort = mockk<UniversePort>(relaxed = true)

    @BeforeEach
    fun setUp() {
        coEvery { universePort.get(any()) } returns null
        // Market is open by default — individual tests override when testing the closed-market path.
        every { universePort.isMarketOpen(any()) } returns true
    }

    private val filledOrder = LegOrder(orderId = 99, status = OrderStatus.FILLED)
    private val pendingOrder = LegOrder(orderId = 99, status = OrderStatus.PENDING)

    private fun buildOpenSpread(creditPerShare: BigDecimal = BigDecimal("1.00")) =
        BullPutSpread(
            id = UUID.randomUUID(),
            symbol = symbol,
            soldLeg = SpreadLeg(soldContract, LegAction.SELL, Money(BigDecimal("1.50")), orderId = 1),
            boughtLeg = SpreadLeg(boughtContract, LegAction.BUY, Money(BigDecimal("0.50")), orderId = 2),
            creditPerShare = creditPerShare,
            maxRiskPerShare = BigDecimal("4.00"),
            status = SpreadStatus.OPEN,
            ivRankAtEntry = 35.0,
            underlyingPriceAtEntry = BigDecimal("500"),
            openedAt = Instant.now(),
        )

    /** Wires a [SpreadManagementService] with the given ports; other deps default to benign stubs. */
    private fun buildService(
        spreadPort: SpreadPort,
        marketDataPort: MarketDataPort,
        orderPort: OrderPort = mockk(relaxed = true),
        executionPort: TradeExecutionPort = mockk(relaxed = true),
        clock: Clock = clockAtEntry,
        positionsPort: cz.solvina.options.domain.features.account.PositionsPort? = null,
    ) = SpreadManagementService(
        spreadPort = spreadPort,
        marketDataPort = marketDataPort,
        orderPort = orderPort,
        universePort = universePort,
        volatilityPort =
            object : VolatilityPort {
                override suspend fun getIvRank(symbol: Symbol) = IvRank(35.0, 0.25, Instant.now())
            },
        executionPort = executionPort,
        config = config,
        clock = clock,
        quoteHealthService = mockk(relaxed = true),
        strategyRegistry = strategyRegistry,
        positionsPort = positionsPort,
    )

    // -------------------------------------------------------------------------
    // Take-profit exit
    // -------------------------------------------------------------------------

    @Test
    fun `take profit closes spread when value falls below the 50 percent threshold`() =
        runTest {
            // credit=$1.00 → TP threshold = $1.00 × 0.50 = $0.50
            // spread value = soldMid($0.30) − boughtMid($0.05) = $0.25  →  below threshold
            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findOpen() } returns listOf(buildOpenSpread())
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns emptyList()
            coEvery { spreadPort.update(any()) } answers { firstArg() }
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("0.30"))
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("0.30"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.05"))
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(BigDecimal("0.05"))
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("500"))
            coEvery { orderPort.placeMarketOrder(any(), any(), any()) } returns filledOrder

            val updated = slot<BullPutSpread>()
            coEvery { spreadPort.update(capture(updated)) } answers { firstArg() }

            buildService(spreadPort, marketDataPort, orderPort).checkExits()

            assertEquals(SpreadStatus.CLOSED_PROFIT, updated.captured.status)
        }

    @Test
    fun `sell-back of bought leg uses market mid price not zero`() =
        runTest {
            // Historical bug: the sell-back limitPrice was always $0 instead of the market mid.
            // This test exercises softClose (limit-order path) where the bug lived.
            // forceCloseSpread (market-order path used by checkExits) has no limit price.
            val spread = buildOpenSpread()
            val boughtMid = BigDecimal("0.05")

            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findById(spread.id!!) } returns spread
            coEvery { spreadPort.update(any()) } answers { firstArg() }
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("0.30"))
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("0.30"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(boughtMid)
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(boughtMid)
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("500"))
            coEvery { orderPort.placeAndAwaitFill(any(), any(), any(), any()) } returns filledOrder

            SpreadManagementService(
                spreadPort = spreadPort,
                marketDataPort = marketDataPort,
                orderPort = orderPort,
                universePort = universePort,
                volatilityPort = mockk(relaxed = true),
                executionPort = mockk(relaxed = true),
                config = config,
                clock = clockAtEntry,
                quoteHealthService = mockk(relaxed = true),
                strategyRegistry = strategyRegistry,
            ).softClose(spread.id!!)

            val sellBackPrice = slot<Money>()
            coVerify {
                orderPort.placeAndAwaitFill(
                    contract = boughtContract,
                    action = LegAction.SELL,
                    limitPrice = capture(sellBackPrice),
                    qty = any(),
                )
            }
            assertEquals(boughtMid, sellBackPrice.captured.amount)
        }

    // -------------------------------------------------------------------------
    // Stop-loss exit
    // -------------------------------------------------------------------------

    @Test
    fun `stop loss closes spread when value exceeds the 150 percent threshold`() =
        runTest {
            // credit=$1.00 → SL threshold = $1.00 × 1.50 = $1.50
            // spread value = $1.70 − $0.10 = $1.60  →  exceeds threshold
            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findOpen() } returns listOf(buildOpenSpread())
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns emptyList()
            coEvery { spreadPort.update(any()) } answers { firstArg() }
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("1.70"))
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("1.70"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("500"))
            coEvery { orderPort.placeMarketOrder(any(), any(), any()) } returns filledOrder

            val updated = slot<BullPutSpread>()
            coEvery { spreadPort.update(capture(updated)) } answers { firstArg() }

            SpreadManagementService(
                spreadPort = spreadPort,
                marketDataPort = marketDataPort,
                orderPort = orderPort,
                universePort = universePort,
                volatilityPort = mockk(relaxed = true),
                executionPort = mockk(relaxed = true),
                config = config,
                clock = clockAtEntry,
                quoteHealthService = mockk(relaxed = true),
                strategyRegistry = strategyRegistry,
            ).checkExits()

            assertEquals(SpreadStatus.CLOSED_STOP, updated.captured.status)
        }

    @Test
    fun `stop loss triggers a 24-hour re-entry block so the scanner does not chase a loser`() =
        runTest {
            // After a stop-loss, re-entering the same position risks doubling down on a losing trade.
            // The rule: block the symbol for stopLossCooldownHours (default 24 h).
            //
            // credit=$1.00, SL threshold=$1.50; spread value=$1.60 → SL fires.
            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()
            val executionPort = RecordingExecutionPort()

            coEvery { spreadPort.findOpen() } returns listOf(buildOpenSpread())
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns emptyList()
            coEvery { spreadPort.update(any()) } answers { firstArg() }
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("1.70"))
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("1.70"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("500"))
            coEvery { orderPort.placeMarketOrder(any(), any(), any()) } returns filledOrder

            buildService(spreadPort, marketDataPort, orderPort, executionPort).checkExits()

            assertEquals(1, executionPort.blockedEntries.size, "blockEntry should have been called exactly once")
            assertEquals(symbol, executionPort.blockedEntries.first().symbol)
            assertEquals(Duration.ofHours(config.stopLossCooldownHours), executionPort.blockedEntries.first().duration)
        }

    @Test
    fun `take profit does not trigger a re-entry block`() =
        runTest {
            // A clean profit exit is not punished — the symbol stays eligible for the next scan.
            // spread value = $0.25 < TP threshold $0.50 → take profit, no block.
            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()
            val executionPort = RecordingExecutionPort()

            coEvery { spreadPort.findOpen() } returns listOf(buildOpenSpread())
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns emptyList()
            coEvery { spreadPort.update(any()) } answers { firstArg() }
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("0.30"))
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("0.30"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.05"))
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(BigDecimal("0.05"))
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("500"))
            coEvery { orderPort.placeMarketOrder(any(), any(), any()) } returns filledOrder

            buildService(spreadPort, marketDataPort, orderPort, executionPort).checkExits()

            assertTrue(executionPort.blockedEntries.isEmpty(), "blockEntry must not be called on a TP exit")
        }

    // -------------------------------------------------------------------------
    // Time exit
    // -------------------------------------------------------------------------

    @Test
    fun `time exit closes spread when fewer than 14 days remain until expiry`() =
        runTest {
            // With 10 days to expiry, gamma risk is too high to hold — close regardless of P&L.
            // Prices are in the hold zone (no TP/SL) to confirm it is the time rule that fires.
            //
            // spread value = $0.60 — above TP($0.50) and below SL($1.50) → only DTE fires.
            val nearExpiry = LocalDate.of(2025, 3, 21).minusDays(10)
            val clock = Clock.fixed(nearExpiry.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), java.time.ZoneOffset.UTC)

            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findOpen() } returns listOf(buildOpenSpread())
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns emptyList()
            coEvery { spreadPort.update(any()) } answers { firstArg() }
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("0.70"))
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("0.70"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("500"))
            coEvery { orderPort.placeMarketOrder(any(), any(), any()) } returns filledOrder

            val updated = slot<BullPutSpread>()
            coEvery { spreadPort.update(capture(updated)) } answers { firstArg() }

            buildService(spreadPort, marketDataPort, orderPort, clock = clock).checkExits()

            assertEquals(SpreadStatus.CLOSED_TIME, updated.captured.status)
        }

    // -------------------------------------------------------------------------
    // Hold zone
    // -------------------------------------------------------------------------

    @Test
    fun `spread stays open when value is between take-profit and stop-loss thresholds`() =
        runTest {
            // TP=$0.50, SL=$1.50; spread value = $0.60 → hold zone, no orders placed.
            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findOpen() } returns listOf(buildOpenSpread())
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns emptyList()
            coEvery { spreadPort.update(any()) } answers { firstArg() }
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("0.70"))
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("0.70"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(BigDecimal("0.10"))

            SpreadManagementService(
                spreadPort = spreadPort,
                marketDataPort = marketDataPort,
                orderPort = orderPort,
                universePort = universePort,
                volatilityPort = mockk(relaxed = true),
                executionPort = mockk(relaxed = true),
                config = config,
                clock = clockAtEntry,
                quoteHealthService = mockk(relaxed = true),
                strategyRegistry = strategyRegistry,
            ).checkExits()

            coVerify(exactly = 0) { orderPort.placeAndAwaitFill(any(), any(), any(), any()) }
            coVerify(exactly = 1) { spreadPort.update(match { it.status == SpreadStatus.OPEN }) }
        }

    // -------------------------------------------------------------------------
    // Strategy seam
    // -------------------------------------------------------------------------

    @Test
    fun `strategy-specific exit from the seam closes a spread the generic rules would hold`() =
        runTest {
            // Generic hold zone (value $0.60 between TP $0.50 and SL $1.50, DTE well above time-exit),
            // but the owning strategy returns an exit — the spread must close on the strategy's signal.
            // This is the path bear-call dividend protection will use; bull put returns null here.
            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>(relaxed = true)

            coEvery { spreadPort.findOpen() } returns listOf(buildOpenSpread())
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns emptyList()
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("0.70"))
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("500"))

            val seamStrategy =
                object : SpreadStrategy {
                    override val id = StrategyId.BULL_PUT

                    override suspend fun strategyExitSignal(spread: Spread) = StrategyExit(SpreadStatus.CLOSED_MANUAL, "SEAM_EXIT")
                }

            val updated = slot<BullPutSpread>()
            coEvery { spreadPort.update(capture(updated)) } answers { firstArg() }

            SpreadManagementService(
                spreadPort = spreadPort,
                marketDataPort = marketDataPort,
                orderPort = orderPort,
                universePort = universePort,
                volatilityPort = mockk(relaxed = true),
                executionPort = mockk(relaxed = true),
                config = config,
                clock = clockAtEntry,
                quoteHealthService = mockk(relaxed = true),
                strategyRegistry = SpreadStrategyRegistry(listOf(seamStrategy)),
            ).checkExits()

            coVerify { orderPort.placeMarketOrder(soldContract, LegAction.BUY, any()) }
            assertEquals(SpreadStatus.CLOSED_MANUAL, updated.captured.status)
            assertEquals("SEAM_EXIT", updated.captured.closeReason)
        }

    // -------------------------------------------------------------------------
    // CLOSING retry — status re-labeling
    // -------------------------------------------------------------------------

    @Test
    fun `stuck CLOSING spread is relabeled CLOSED_PROFIT when market recovers to profit territory`() =
        runTest {
            // Scenario: the stop-loss fired and left the spread in CLOSING, but IBKR orders
            // timed out. By the next monitor cycle the market recovered. Since we're closing
            // at a profit, re-labeling as CLOSED_PROFIT gives an honest journal entry.
            //
            // Original trigger: CLOSED_STOP (stored as closeReason).
            // Current market:  spread value = $0.20  ≤  TP threshold $0.50 → CLOSED_PROFIT.
            val closingSpread =
                buildOpenSpread(creditPerShare = BigDecimal("1.00")).copy(
                    status = SpreadStatus.CLOSING,
                    closeReason = SpreadStatus.CLOSED_STOP.name,
                )

            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findOpen() } returns emptyList()
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns listOf(closingSpread)
            coEvery { spreadPort.update(any()) } answers { firstArg() }
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("0.25"))
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("0.25"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.05"))
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(BigDecimal("0.05"))
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("510"))
            coEvery { orderPort.placeMarketOrder(any(), any(), any()) } returns filledOrder

            val updated = slot<BullPutSpread>()
            coEvery { spreadPort.update(capture(updated)) } answers { firstArg() }

            buildService(spreadPort, marketDataPort, orderPort).checkExits()

            assertEquals(
                SpreadStatus.CLOSED_PROFIT,
                updated.captured.status,
                "Recovered SL must be relabeled CLOSED_PROFIT — a stale CLOSED_STOP label misstates the journal",
            )
        }

    @Test
    fun `stuck CLOSING spread keeps CLOSED_STOP and blocks re-entry when still above stop-loss threshold`() =
        runTest {
            // Spread is stuck in CLOSING and the market has NOT recovered — still a loss.
            // Status stays CLOSED_STOP and the re-entry block is applied.
            //
            // spread value = $1.60  ≥  SL threshold $1.50 → CLOSED_STOP + block.
            val closingSpread =
                buildOpenSpread(creditPerShare = BigDecimal("1.00")).copy(
                    status = SpreadStatus.CLOSING,
                    closeReason = SpreadStatus.CLOSED_STOP.name,
                )

            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()
            val executionPort = RecordingExecutionPort()

            coEvery { spreadPort.findOpen() } returns emptyList()
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns listOf(closingSpread)
            coEvery { spreadPort.update(any()) } answers { firstArg() }
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("1.70"))
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("1.70"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("495"))
            coEvery { orderPort.placeMarketOrder(any(), any(), any()) } returns filledOrder

            val updated = slot<BullPutSpread>()
            coEvery { spreadPort.update(capture(updated)) } answers { firstArg() }

            buildService(spreadPort, marketDataPort, orderPort, executionPort).checkExits()

            assertEquals(SpreadStatus.CLOSED_STOP, updated.captured.status)
            assertEquals(1, executionPort.blockedEntries.size, "Re-entry must be blocked after a confirmed stop-loss")
        }

    @Test
    fun `stuck CLOSING spread preserves original trigger when value is between TP and SL thresholds`() =
        runTest {
            // A time-triggered close that got stuck (e.g. limit order never filled) and now the
            // market is in the hold zone. The time rule is still the correct reason — preserve it.
            //
            // Original trigger: CLOSED_TIME.
            // spread value = $0.80 — above TP($0.50) and below SL($1.50) → neither rule applies.
            val closingSpread =
                buildOpenSpread(creditPerShare = BigDecimal("1.00")).copy(
                    status = SpreadStatus.CLOSING,
                    closeReason = SpreadStatus.CLOSED_TIME.name,
                )

            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findOpen() } returns emptyList()
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns listOf(closingSpread)
            coEvery { spreadPort.update(any()) } answers { firstArg() }
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("0.90"))
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("0.90"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("500"))
            coEvery { orderPort.placeMarketOrder(any(), any(), any()) } returns filledOrder

            val updated = slot<BullPutSpread>()
            coEvery { spreadPort.update(capture(updated)) } answers { firstArg() }

            buildService(spreadPort, marketDataPort, orderPort).checkExits()

            assertEquals(SpreadStatus.CLOSED_TIME, updated.captured.status)
        }

    // -------------------------------------------------------------------------
    // Exchange hours gating
    // -------------------------------------------------------------------------

    @Test
    fun `spreads are skipped entirely when their exchange is not in trading hours`() =
        runTest {
            // EU equities trade 09:00–17:30 CET; checking at e.g. 20:00 CET must be a no-op.
            // The monitor must never query market data or place orders outside exchange hours.
            every { universePort.isMarketOpen(any()) } returns false

            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findOpen() } returns listOf(buildOpenSpread())
            coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns emptyList()

            buildService(spreadPort, marketDataPort, orderPort).checkExits()

            coVerify(exactly = 0) { marketDataPort.getOptionMid(any()) }
            coVerify(exactly = 0) { orderPort.placeAndAwaitFill(any(), any(), any(), any()) }
            coVerify(exactly = 0) { orderPort.placeMarketOrder(any(), any(), any()) }
        }

    // -------------------------------------------------------------------------
    // Soft-close (limit-order manual exit) edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `worthless sold leg closes the spread without placing a buy-back order`() =
        runTest {
            // When the sold put is worth $0.00 (deep OTM, near expiry), placing a buy-back
            // limit order at $0 would never fill. Skip it and close directly.
            val spread = buildOpenSpread()
            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findById(spread.id!!) } returns spread
            coEvery { spreadPort.update(any()) } answers { firstArg() }
            // sold leg is worthless; bought leg has some remaining value
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("0.00"))
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("0.00"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.05"))
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(BigDecimal("0.05"))
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("490"))

            buildService(spreadPort, marketDataPort, orderPort).softClose(spread.id!!)

            // No buy-back order should be submitted when the option is worthless
            coVerify(exactly = 0) { orderPort.placeAndAwaitFill(soldContract, LegAction.BUY, any(), any()) }
        }

    @Test
    fun `buy-back order that cannot fill leaves the spread in CLOSING for the next retry cycle`() =
        runTest {
            // If our limit buy-back order does not fill (e.g. market moved against us while
            // the order was in flight), leave the spread in CLOSING so the monitor retries.
            val spread = buildOpenSpread()
            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            val updates = mutableListOf<BullPutSpread>()
            coEvery { spreadPort.findById(spread.id!!) } returns spread
            coEvery { spreadPort.update(capture(updates)) } answers { firstArg() }
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("0.80"))
            coEvery { marketDataPort.getOptionMidLive(soldContract) } returns Money(BigDecimal("0.80"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getOptionMidLive(boughtContract) } returns Money(BigDecimal("0.10"))
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("495"))
            // Order submitted but not filled
            coEvery { orderPort.placeAndAwaitFill(any(), any(), any(), any()) } returns pendingOrder

            buildService(spreadPort, marketDataPort, orderPort).softClose(spread.id!!)

            // Last persisted state must be CLOSING — the sell-back leg is never attempted
            val closingState = updates.last { it.status == SpreadStatus.CLOSING }
            assertEquals(SpreadStatus.CLOSING, closingState.status)
            coVerify(exactly = 0) { orderPort.placeAndAwaitFill(boughtContract, LegAction.SELL, any(), any()) }
        }

    // -------------------------------------------------------------------------
    // Idempotent close (C5)
    // -------------------------------------------------------------------------

    @Test
    fun `force-close skips a leg already flat in IBKR and only closes the still-held leg`() =
        runTest {
            // Simulate a retry after a partial close: the bought (long) leg already filled, so only
            // the sold (short) leg remains. The close must NOT re-fire the bought leg (which would
            // open an unintended opposite position) — it should only buy back the still-open short.
            val spread = buildOpenSpread()
            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findById(spread.id!!) } returns spread
            coEvery { spreadPort.update(any()) } answers { firstArg() }
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("0.20"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.05"))
            coEvery { marketDataPort.getUnderlyingPrice(any()) } returns Money(BigDecimal("495"))

            // Only the sold strike is still held; placing its buy-back clears it so verification passes.
            val heldStrikes = mutableSetOf(soldContract.strike)
            val positionsPort =
                object : cz.solvina.options.domain.features.account.PositionsPort {
                    override suspend fun getPositions() =
                        heldStrikes.map { strike ->
                            cz.solvina.options.domain.features.account.AccountPosition(
                                account = "DU1",
                                symbol = symbol.value,
                                secType = "OPT",
                                currency = "USD",
                                expiry = expiry,
                                strike = strike,
                                // IBKR reports the right as "P"/"C" (Types.Right.getApiString),
                                // not "Put"/"Call" — match real account data so this test guards
                                // positionMatchesLeg against the orphan-creating mismatch regression.
                                optionRight = "P",
                                quantity = BigDecimal("-1"),
                                avgCost = BigDecimal.ZERO,
                            )
                        }
                }
            coEvery { orderPort.placeMarketOrder(soldContract, LegAction.BUY, any()) } coAnswers {
                heldStrikes.remove(soldContract.strike)
                filledOrder
            }

            buildService(spreadPort, marketDataPort, orderPort, positionsPort = positionsPort)
                .forceClose(spread.id!!)

            coVerify(exactly = 1) { orderPort.placeMarketOrder(soldContract, LegAction.BUY, any()) }
            coVerify(exactly = 0) { orderPort.placeMarketOrder(boughtContract, LegAction.SELL, any()) }
        }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Captures [blockEntry] calls so tests can assert which symbol was blocked and for how long.
     */
    private class RecordingExecutionPort : TradeExecutionPort {
        data class BlockedEntry(
            val symbol: Symbol,
            val duration: Duration,
        )

        val blockedEntries = mutableListOf<BlockedEntry>()

        override suspend fun execute(request: TradeExecutionRequest) = TradeExecutionResult(ExecutionOutcome.DIAGNOSTIC_SKIPPED)

        override fun isInFlight(symbol: Symbol) = false

        override fun isCoolingDown(symbol: Symbol) = false

        override fun blockEntry(
            symbol: Symbol,
            duration: Duration,
        ) {
            blockedEntries.add(BlockedEntry(symbol, duration))
        }
    }
}
