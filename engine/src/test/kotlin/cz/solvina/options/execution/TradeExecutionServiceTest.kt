package cz.solvina.options.execution

import cz.solvina.options.domain.features.account.AccountDetail
import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.execution.PreTradeValidator
import cz.solvina.options.domain.features.execution.SpreadEntryWriterRegistry
import cz.solvina.options.domain.features.execution.TradeExecutionService
import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.MarketTickPort
import cz.solvina.options.domain.features.market.SpreadCreditTick
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.LegQuotes
import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.scanner.BullPutScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.scanner.StrategyParamsRegistry
import cz.solvina.options.domain.features.spread.BullPutSpreadPort
import cz.solvina.options.domain.features.spread.SpreadQueryFacade
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.spread.strategy.bullput.BullPutSpreadEntryWriter
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import cz.solvina.options.testutil.InMemoryBearCallSpreadPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalCoroutinesApi::class)
class TradeExecutionServiceTest {
    // -------------------------------------------------------------------------
    // Shared fixtures
    // -------------------------------------------------------------------------

    private val symbol = Symbol("SPY")
    private val expiry = LocalDate.of(2025, 3, 21)
    private val soldContract = OptionContract(symbol, expiry, BigDecimal("480"), OptionType.PUT)
    private val boughtContract = OptionContract(symbol, expiry, BigDecimal("475"), OptionType.PUT)

    private val baseConfig =
        ScannerConfig(
            watchlist = listOf("SPY"),
            executionTimeoutMinutes = 1,
            ticksBeforePriceAdjust = 3,
            maxLegBidAskSpreadPct = 0.30,
            feePerContract = java.math.BigDecimal.ZERO,
        )

    // Bull-put drift default for the execution tests; the drift-abort test overrides it.
    private val baseBullPut = BullPutScannerConfig(driftProtectionPct = 0.02)

    private fun buildRequest(
        targetCredit: BigDecimal = BigDecimal("1.00"),
        floorCredit: BigDecimal = BigDecimal("0.50"),
        underlyingPrice: BigDecimal = BigDecimal("500"),
        soldBidAsk: Pair<BigDecimal, BigDecimal> = Pair(BigDecimal("0.95"), BigDecimal("1.05")),
        boughtBidAsk: Pair<BigDecimal, BigDecimal> = Pair(BigDecimal("0.45"), BigDecimal("0.55")),
    ) = TradeExecutionRequest(
        soldContract = soldContract,
        boughtContract = boughtContract,
        underlyingSymbol = symbol,
        targetCredit = targetCredit,
        floorCredit = floorCredit,
        maxRiskPerShare = BigDecimal("4.00"),
        ivRankAtEntry = 35.0,
        soldBid = soldBidAsk.first,
        soldAsk = soldBidAsk.second,
        boughtBid = boughtBidAsk.first,
        boughtAsk = boughtBidAsk.second,
        boughtMid = BigDecimal("0.50"),
        underlyingPriceAtEntry = underlyingPrice,
    )

    private fun buildAccountPort(availableFunds: BigDecimal = BigDecimal("10000")): AccountPort =
        object : AccountPort {
            override val accountDetail =
                MutableStateFlow(
                    AccountDetail(
                        totalCapital = Money(BigDecimal("50000")),
                        availableFunds = Money(availableFunds),
                    ),
                )
        }

    private fun TestScope.buildService(
        marketTickPort: MarketTickPort,
        orderExecutionPort: OrderExecutionPort,
        spreadPort: BullPutSpreadPort = InMemoryBullPutSpreadPort(),
        accountPort: AccountPort = buildAccountPort(),
        config: ScannerConfig = baseConfig,
        bullPutConfig: BullPutScannerConfig = baseBullPut,
    ): TradeExecutionService {
        val spreadQuery = SpreadQueryFacade(spreadPort, InMemoryBearCallSpreadPort())
        return TradeExecutionService(
            marketTickPort = marketTickPort,
            orderExecutionPort = orderExecutionPort,
            spreadQuery = spreadQuery,
            writerRegistry = SpreadEntryWriterRegistry(listOf(BullPutSpreadEntryWriter(spreadPort, Clock.systemUTC()))),
            validator =
                PreTradeValidator(
                    spreadQuery = spreadQuery,
                    orderExecutionPort = orderExecutionPort,
                    accountPort = accountPort,
                    config = config,
                ),
            config = config,
            strategyParams = StrategyParamsRegistry(listOf(bullPutConfig, BearCallScannerConfig())),
            clock = Clock.systemUTC(),
            scope = backgroundScope,
        )
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `fills_immediately_at_target_credit`() =
        runTest {
            val fillChannel = Channel<OrderStatus>(1)

            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, tickFlowAtCredit(1.00)),
                    orderExecutionPort =
                        immediateComboOrderPort(fillChannel) { _, _, _ ->
                            fillChannel.send(OrderStatus.FILLED)
                        },
                )

            val result = service.execute(buildRequest(targetCredit = BigDecimal("1.00")))

            assertEquals(ExecutionOutcome.FILLED, result.outcome)
            assertNotNull(result.comboOrderId)
        }

    @Test
    fun `fills when the credit stream is hot and never completes (E1 regression)`() =
        runTest {
            // The production credit stream is a callbackFlow — it emits continuously and NEVER
            // completes. The other tests use a finite `flow { emit() }`, so the collect inside
            // calculateFreshCredit returns on its own and the bug is hidden. This stub mirrors
            // production: one usable tick at the target credit, then it stays open forever.
            //
            // E1: calculateFreshCredit collects this hot flow inside withTimeout(3s) without
            // breaking after the first tick, so the timeout always fires and the freshly computed
            // credit is discarded → the entry aborts with MARKET_MOVED_TOO_FAR and no order is
            // ever submitted. With the fix (first()/break) the first tick yields the credit and
            // the entry proceeds to FILLED.
            val fillChannel = Channel<OrderStatus>(1)

            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, hotTickFlowAtCredit(1.00)),
                    orderExecutionPort =
                        immediateComboOrderPort(fillChannel) { _, _, _ ->
                            fillChannel.send(OrderStatus.FILLED)
                        },
                )

            val result = service.execute(buildRequest(targetCredit = BigDecimal("1.00")))

            assertEquals(
                ExecutionOutcome.FILLED,
                result.outcome,
                "A hot (never-completing) credit stream emitting a usable tick must still submit and " +
                    "fill. MARKET_MOVED_TOO_FAR here means E1 is present: the computed credit was " +
                    "discarded when the 3s freshness timeout fired on the never-completing collect.",
            )
            assertNotNull(result.comboOrderId)
        }

    @Test
    fun `rejects entry with CAP_REACHED when active spreads are at maxOpenSpreads`() =
        runTest {
            // Seed maxOpenSpreads OPEN spreads on *other* symbols so the per-symbol exposure check
            // passes but the global cap is already full. A new SPY entry must be rejected (C1).
            val spreadPort = InMemoryBullPutSpreadPort()
            spreadPort.save(buildOpenSpread().copy(id = UUID.randomUUID(), symbol = Symbol("AAA")))
            spreadPort.save(buildOpenSpread().copy(id = UUID.randomUUID(), symbol = Symbol("BBB")))

            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, tickFlowAtCredit(1.00)),
                    orderExecutionPort = noopComboOrderPort(),
                    spreadPort = spreadPort,
                    config = baseConfig.copy(maxOpenSpreads = 2),
                )

            val result = service.execute(buildRequest(targetCredit = BigDecimal("1.00")))

            assertEquals(ExecutionOutcome.CAP_REACHED, result.outcome)
        }

    @Test
    fun `ladders_price_down_over_ticks`() =
        runTest {
            val submitCount = AtomicInteger(0)

            val orderPort =
                object : OrderExecutionPort {
                    private val nextId = AtomicInteger(100)
                    private val deferreds = ConcurrentHashMap<Int, CompletableDeferred<OrderStatus>>()

                    override suspend fun submitComboLimitOrder(
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        netCredit: Money,
                        qty: Int,
                        legQuotes: LegQuotes?,
                    ): Int {
                        val id = nextId.getAndIncrement()
                        val deferred = CompletableDeferred<OrderStatus>()
                        deferreds[id] = deferred
                        val n = submitCount.incrementAndGet()
                        if (n == 2) deferred.complete(OrderStatus.FILLED)
                        return id
                    }

                    override suspend fun awaitFill(orderId: Int): OrderStatus = deferreds[orderId]?.await() ?: OrderStatus.CANCELLED

                    override suspend fun cancelAndAwait(orderId: Int): OrderStatus {
                        deferreds[orderId]?.complete(OrderStatus.CANCELLED)
                        return OrderStatus.CANCELLED
                    }

                    override suspend fun replaceComboWithNewPrice(
                        existingOrderId: Int,
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        newCredit: Money,
                        qty: Int,
                    ): Int {
                        cancelAndAwait(existingOrderId)
                        return submitComboLimitOrder(soldContract, boughtContract, newCredit, qty)
                    }

                    override suspend fun getSymbolsWithOpenOrders(): Set<Symbol> = emptySet()

                    override fun consumeFillPrice(orderId: Int): java.math.BigDecimal? = null
                }

            val service =
                buildService(
                    marketTickPort =
                        fixedMarketTickPort(
                            underlyingPrice = 500.0,
                            creditFlow = tickFlowLaddering(listOf(1.00, 1.00, 1.00, 1.00, 1.00)),
                        ),
                    orderExecutionPort = orderPort,
                    config = baseConfig.copy(ticksBeforePriceAdjust = 3),
                )

            val result = service.execute(buildRequest(targetCredit = BigDecimal("1.00")))

            assertEquals(ExecutionOutcome.FILLED, result.outcome)
            assertTrue(submitCount.get() >= 2, "Expected at least 2 order submissions (initial + ladder)")
        }

    @Test
    fun `never_goes_below_floor`() =
        runTest {
            val submittedCredits = mutableListOf<BigDecimal>()

            val orderPort =
                object : OrderExecutionPort {
                    private val nextId = AtomicInteger(1)

                    override suspend fun submitComboLimitOrder(
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        netCredit: Money,
                        qty: Int,
                        legQuotes: LegQuotes?,
                    ): Int {
                        submittedCredits.add(netCredit.amount)
                        return nextId.getAndIncrement()
                    }

                    override suspend fun awaitFill(orderId: Int): OrderStatus {
                        delay(Long.MAX_VALUE)
                        return OrderStatus.CANCELLED
                    }

                    override suspend fun cancelAndAwait(orderId: Int): OrderStatus = OrderStatus.CANCELLED

                    override suspend fun replaceComboWithNewPrice(
                        existingOrderId: Int,
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        newCredit: Money,
                        qty: Int,
                    ): Int = submitComboLimitOrder(soldContract, boughtContract, newCredit, qty)

                    override suspend fun getSymbolsWithOpenOrders(): Set<Symbol> = emptySet()

                    override fun consumeFillPrice(orderId: Int): java.math.BigDecimal? = null
                }

            val service =
                buildService(
                    marketTickPort =
                        fixedMarketTickPort(
                            underlyingPrice = 500.0,
                            creditFlow = tickFlowLaddering(List(20) { 0.75 }), // Emit ticks at 0.75
                        ),
                    orderExecutionPort = orderPort,
                    config = baseConfig.copy(ticksBeforePriceAdjust = 5), // Ladder every 5 ticks
                )

            val result =
                service.execute(
                    buildRequest(targetCredit = BigDecimal("0.75"), floorCredit = BigDecimal("0.50")),
                )

            // Verify no order goes below floor (regardless of outcome)
            assertTrue(
                submittedCredits.all { it >= BigDecimal("0.50") },
                "Order submitted below floor: $submittedCredits",
            )
        }

    @Test
    fun `drift_aborts_execution`() =
        runTest {
            val service =
                buildService(
                    marketTickPort =
                        object : MarketTickPort {
                            override fun streamUnderlyingPrice(symbol: Symbol): Flow<Double> =
                                underlyingPriceFlowWithDrift(500.0, 0.03) // 3% drift > 2% threshold

                            override fun streamSpreadCredit(
                                soldContract: OptionContract,
                                boughtContract: OptionContract,
                            ): Flow<SpreadCreditTick> = tickFlowAtCredit(1.00)
                        },
                    orderExecutionPort = neverFillOrderPort(),
                    bullPutConfig = BullPutScannerConfig(driftProtectionPct = 0.02),
                )

            val result = service.execute(buildRequest(underlyingPrice = BigDecimal("500")))

            assertEquals(ExecutionOutcome.DRIFT_ABORTED, result.outcome)
        }

    @Test
    fun `timeout_cancels_order`() =
        runTest {
            var cancelCalled = false
            val orderPort =
                object : OrderExecutionPort {
                    override suspend fun submitComboLimitOrder(
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        netCredit: Money,
                        qty: Int,
                        legQuotes: LegQuotes?,
                    ): Int = 1

                    override suspend fun awaitFill(orderId: Int): OrderStatus {
                        delay(Long.MAX_VALUE)
                        return OrderStatus.CANCELLED
                    }

                    override suspend fun cancelAndAwait(orderId: Int): OrderStatus {
                        cancelCalled = true
                        return OrderStatus.CANCELLED
                    }

                    override suspend fun replaceComboWithNewPrice(
                        existingOrderId: Int,
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        newCredit: Money,
                        qty: Int,
                    ): Int = 1

                    override suspend fun getSymbolsWithOpenOrders(): Set<Symbol> = emptySet()

                    override fun consumeFillPrice(orderId: Int): java.math.BigDecimal? = null
                }

            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, tickFlowAtCredit(1.00)),
                    orderExecutionPort = orderPort,
                    config = baseConfig.copy(executionTimeoutMinutes = 1),
                )

            launch { advanceTimeBy(61_000) }

            val result = service.execute(buildRequest())

            assertEquals(ExecutionOutcome.TIMED_OUT, result.outcome)
            assertTrue(cancelCalled, "cancelAndAwait should have been called on timeout")
        }

    @Test
    fun `stale fill cancels the newer replacement order before honoring it`() =
        runTest {
            // Simulates the race where an older (already-laddered-away) order actually fills right
            // as a replacement order is in flight. The execution loop must cancel the newer
            // replacement before adopting the stale fill — otherwise both orders can end up filled.
            val deferreds = ConcurrentHashMap<Int, CompletableDeferred<OrderStatus>>()
            val cancelledOrderIds = mutableListOf<Int>()
            val nextId = AtomicInteger(1)
            val secondOrderReady = Channel<Unit>(1)

            val orderPort =
                object : OrderExecutionPort {
                    override suspend fun submitComboLimitOrder(
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        netCredit: Money,
                        qty: Int,
                        legQuotes: LegQuotes?,
                    ): Int {
                        val id = nextId.getAndIncrement()
                        deferreds[id] = CompletableDeferred()
                        if (id == 2) secondOrderReady.trySend(Unit)
                        return id
                    }

                    override suspend fun awaitFill(orderId: Int): OrderStatus = deferreds[orderId]?.await() ?: OrderStatus.CANCELLED

                    override suspend fun cancelAndAwait(orderId: Int): OrderStatus {
                        cancelledOrderIds.add(orderId)
                        deferreds[orderId]?.complete(OrderStatus.CANCELLED)
                        return OrderStatus.CANCELLED
                    }

                    override suspend fun replaceComboWithNewPrice(
                        existingOrderId: Int,
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        newCredit: Money,
                        qty: Int,
                    ): Int =
                        // Mirrors the real-world race: the replacement is submitted while the old
                        // order's fill deferred is still unresolved (IBKR hasn't confirmed the cancel).
                        submitComboLimitOrder(soldContract, boughtContract, newCredit, qty, null)

                    override suspend fun getSymbolsWithOpenOrders(): Set<Symbol> = emptySet()

                    override fun consumeFillPrice(orderId: Int): java.math.BigDecimal? = null
                }

            val service =
                buildService(
                    marketTickPort =
                        fixedMarketTickPort(
                            underlyingPrice = 500.0,
                            creditFlow = tickFlowLaddering(listOf(1.00, 1.00, 1.00)),
                        ),
                    orderExecutionPort = orderPort,
                    config = baseConfig.copy(ticksBeforePriceAdjust = 1),
                )

            val resultDeferred = backgroundScope.async { service.execute(buildRequest(targetCredit = BigDecimal("1.00"))) }

            secondOrderReady.receive() // wait for the ladder's replacement order (id=2) to be submitted
            deferreds[1]!!.complete(OrderStatus.FILLED) // honor the stale (older) order's fill

            val result = resultDeferred.await()

            assertEquals(ExecutionOutcome.FILLED, result.outcome)
            assertEquals(1, result.comboOrderId, "the stale (older) order id should be recorded as the fill")
            assertTrue(
                2 in cancelledOrderIds,
                "the newer replacement order (id=2) must be cancelled once the stale fill is honored",
            )
        }

    @Test
    fun `ladder does not launch a duplicate fill watcher when the replacement order id is unchanged`() =
        runTest {
            // Mirrors OrderExecutionPort.replaceComboWithNewPrice returning the SAME order id when the
            // old order actually filled instead of being cancelled (ReplacementCancelResult.Filled) —
            // the execution loop must not spin up a second, duplicate fill watcher for that id.
            val awaitFillCallCount = AtomicInteger(0)
            val fillDeferred = CompletableDeferred<OrderStatus>()
            val ladderStepped = Channel<Unit>(1)

            val orderPort =
                object : OrderExecutionPort {
                    override suspend fun submitComboLimitOrder(
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        netCredit: Money,
                        qty: Int,
                        legQuotes: LegQuotes?,
                    ): Int = 1

                    override suspend fun awaitFill(orderId: Int): OrderStatus {
                        awaitFillCallCount.incrementAndGet()
                        return fillDeferred.await()
                    }

                    override suspend fun cancelAndAwait(orderId: Int): OrderStatus = OrderStatus.CANCELLED

                    override suspend fun replaceComboWithNewPrice(
                        existingOrderId: Int,
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        newCredit: Money,
                        qty: Int,
                    ): Int {
                        ladderStepped.trySend(Unit)
                        return existingOrderId
                    }

                    override suspend fun getSymbolsWithOpenOrders(): Set<Symbol> = emptySet()

                    override fun consumeFillPrice(orderId: Int): java.math.BigDecimal? = null
                }

            val service =
                buildService(
                    marketTickPort =
                        fixedMarketTickPort(
                            underlyingPrice = 500.0,
                            creditFlow = tickFlowLaddering(listOf(1.00, 1.00, 1.00)),
                        ),
                    orderExecutionPort = orderPort,
                    config = baseConfig.copy(ticksBeforePriceAdjust = 1),
                )

            val resultDeferred = backgroundScope.async { service.execute(buildRequest(targetCredit = BigDecimal("1.00"))) }

            ladderStepped.receive() // wait for at least one ladder step (id-unchanged path) to occur
            fillDeferred.complete(OrderStatus.FILLED)

            val result = resultDeferred.await()

            assertEquals(ExecutionOutcome.FILLED, result.outcome)
            assertEquals(1, result.comboOrderId)
            assertEquals(
                1,
                awaitFillCallCount.get(),
                "the ladder must not launch a second fill watcher for an unchanged order id",
            )
            // The order rested (and filled) at the ORIGINAL credit — the ladder's decremented credit
            // must not be recorded when no replacement was actually submitted.
            assertEquals(
                0,
                BigDecimal("1.0000").compareTo(result.creditAchieved),
                "an unchanged order id must retain the original credit, not the decremented ladder step",
            )
        }

    @Test
    fun `a fill that races the execution-timeout cancel is recorded as FILLED not TIMED_OUT`() =
        runTest {
            // The execution timeout fires and cancelAndAwait discovers the order actually FILLED
            // during the cancel — a real position exists and must be persisted as OPEN, never as
            // CLOSED_TIMEOUT (which would leave it live at the broker but untracked by the engine).
            val orderPort =
                object : OrderExecutionPort {
                    override suspend fun submitComboLimitOrder(
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        netCredit: Money,
                        qty: Int,
                        legQuotes: LegQuotes?,
                    ): Int = 1

                    override suspend fun awaitFill(orderId: Int): OrderStatus {
                        delay(Long.MAX_VALUE)
                        return OrderStatus.CANCELLED
                    }

                    override suspend fun cancelAndAwait(orderId: Int): OrderStatus = OrderStatus.FILLED

                    override suspend fun replaceComboWithNewPrice(
                        existingOrderId: Int,
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        newCredit: Money,
                        qty: Int,
                    ): Int = existingOrderId

                    override suspend fun getSymbolsWithOpenOrders(): Set<Symbol> = emptySet()

                    override fun consumeFillPrice(orderId: Int): java.math.BigDecimal? = null
                }

            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, tickFlowAtCredit(1.00)),
                    orderExecutionPort = orderPort,
                    config = baseConfig.copy(executionTimeoutMinutes = 1),
                )

            val resultDeferred = backgroundScope.async { service.execute(buildRequest(targetCredit = BigDecimal("1.00"))) }
            advanceTimeBy(61_000)
            val result = resultDeferred.await()

            assertEquals(
                ExecutionOutcome.FILLED,
                result.outcome,
                "a fill discovered by the timeout's cancel must be honored, not recorded as TIMED_OUT",
            )
        }

    @Test
    fun `exposure_rejects_duplicate`() =
        runTest {
            val spreadPort =
                object : InMemoryBullPutSpreadPort() {
                    override suspend fun findByStatus(status: SpreadStatus) =
                        if (status == SpreadStatus.OPEN) listOf(buildOpenSpread()) else emptyList()
                }

            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, flow {}),
                    orderExecutionPort = neverFillOrderPort(),
                    spreadPort = spreadPort,
                )

            val result = service.execute(buildRequest())

            assertEquals(ExecutionOutcome.EXPOSURE_REJECTED, result.outcome)
        }

    @Test
    fun `capital_rejects_underfunded`() =
        runTest {
            // maxRiskPerContract = 4.00 × 100 = 400; available = 300
            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, flow {}),
                    orderExecutionPort = neverFillOrderPort(),
                    accountPort = buildAccountPort(availableFunds = BigDecimal("300")),
                )

            val result = service.execute(buildRequest())

            assertEquals(ExecutionOutcome.CAPITAL_REJECTED, result.outcome)
        }

    @Test
    fun `liquidity_rejects_wide_spread`() =
        runTest {
            // sold bid-ask spread = (1.50 - 0.10) / mid(0.80) ≈ 175 % > 30 %
            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, flow {}),
                    orderExecutionPort = neverFillOrderPort(),
                )

            val result =
                service.execute(
                    buildRequest(soldBidAsk = Pair(BigDecimal("0.10"), BigDecimal("1.50"))),
                )

            assertEquals(ExecutionOutcome.LIQUIDITY_REJECTED, result.outcome)
        }

    // -------------------------------------------------------------------------
    // Inline stubs / helpers
    // -------------------------------------------------------------------------

    private fun fixedMarketTickPort(
        underlyingPrice: Double,
        creditFlow: Flow<SpreadCreditTick>,
    ): MarketTickPort =
        object : MarketTickPort {
            override fun streamUnderlyingPrice(symbol: Symbol): Flow<Double> = flow { emit(underlyingPrice) }

            override fun streamSpreadCredit(
                soldContract: OptionContract,
                boughtContract: OptionContract,
            ): Flow<SpreadCreditTick> = creditFlow
        }

    private fun immediateComboOrderPort(
        fillChannel: Channel<OrderStatus>,
        onSubmit: suspend (OptionContract, OptionContract, Money) -> Unit = { _, _, _ -> },
    ): OrderExecutionPort {
        val nextId = AtomicInteger(1)
        return object : OrderExecutionPort {
            override suspend fun submitComboLimitOrder(
                soldContract: OptionContract,
                boughtContract: OptionContract,
                netCredit: Money,
                qty: Int,
                legQuotes: LegQuotes?,
            ): Int {
                onSubmit(soldContract, boughtContract, netCredit)
                return nextId.getAndIncrement()
            }

            override suspend fun awaitFill(orderId: Int): OrderStatus = fillChannel.receive()

            override suspend fun cancelAndAwait(orderId: Int): OrderStatus = OrderStatus.CANCELLED

            override suspend fun replaceComboWithNewPrice(
                existingOrderId: Int,
                soldContract: OptionContract,
                boughtContract: OptionContract,
                newCredit: Money,
                qty: Int,
            ): Int = submitComboLimitOrder(soldContract, boughtContract, newCredit, qty)

            override suspend fun getSymbolsWithOpenOrders(): Set<Symbol> = emptySet()

            override fun consumeFillPrice(orderId: Int): java.math.BigDecimal? = null
        }
    }

    /** Like [immediateComboOrderPort], but reports [reportedFillPrice] via consumeFillPrice on fill. */
    private fun reportedFillOrderPort(
        fillChannel: Channel<OrderStatus>,
        reportedFillPrice: BigDecimal,
        onSubmit: suspend (OptionContract, OptionContract, Money) -> Unit = { _, _, _ -> },
    ): OrderExecutionPort {
        val nextId = AtomicInteger(1)
        return object : OrderExecutionPort {
            override suspend fun submitComboLimitOrder(
                soldContract: OptionContract,
                boughtContract: OptionContract,
                netCredit: Money,
                qty: Int,
                legQuotes: LegQuotes?,
            ): Int {
                onSubmit(soldContract, boughtContract, netCredit)
                return nextId.getAndIncrement()
            }

            override suspend fun awaitFill(orderId: Int): OrderStatus = fillChannel.receive()

            override suspend fun cancelAndAwait(orderId: Int): OrderStatus = OrderStatus.CANCELLED

            override suspend fun replaceComboWithNewPrice(
                existingOrderId: Int,
                soldContract: OptionContract,
                boughtContract: OptionContract,
                newCredit: Money,
                qty: Int,
            ): Int = submitComboLimitOrder(soldContract, boughtContract, newCredit, qty)

            override suspend fun getSymbolsWithOpenOrders(): Set<Symbol> = emptySet()

            override fun consumeFillPrice(orderId: Int): BigDecimal = reportedFillPrice
        }
    }

    private fun noopComboOrderPort(): OrderExecutionPort =
        object : OrderExecutionPort {
            override suspend fun submitComboLimitOrder(
                soldContract: OptionContract,
                boughtContract: OptionContract,
                netCredit: Money,
                qty: Int,
                legQuotes: LegQuotes?,
            ): Int = error("submitComboLimitOrder must not be reached when the cap rejects the entry")

            override suspend fun awaitFill(orderId: Int): OrderStatus = OrderStatus.CANCELLED

            override suspend fun cancelAndAwait(orderId: Int): OrderStatus = OrderStatus.CANCELLED

            override suspend fun replaceComboWithNewPrice(
                existingOrderId: Int,
                soldContract: OptionContract,
                boughtContract: OptionContract,
                newCredit: Money,
                qty: Int,
            ): Int = error("replaceComboWithNewPrice must not be reached when the cap rejects the entry")

            override suspend fun getSymbolsWithOpenOrders(): Set<Symbol> = emptySet()

            override fun consumeFillPrice(orderId: Int): java.math.BigDecimal? = null
        }

    private fun neverFillOrderPort(): OrderExecutionPort {
        val nextId = AtomicInteger(1)
        return object : OrderExecutionPort {
            override suspend fun submitComboLimitOrder(
                soldContract: OptionContract,
                boughtContract: OptionContract,
                netCredit: Money,
                qty: Int,
                legQuotes: LegQuotes?,
            ): Int = nextId.getAndIncrement()

            override suspend fun awaitFill(orderId: Int): OrderStatus {
                delay(Long.MAX_VALUE)
                return OrderStatus.CANCELLED
            }

            override suspend fun cancelAndAwait(orderId: Int): OrderStatus = OrderStatus.CANCELLED

            override suspend fun replaceComboWithNewPrice(
                existingOrderId: Int,
                soldContract: OptionContract,
                boughtContract: OptionContract,
                newCredit: Money,
                qty: Int,
            ): Int = nextId.getAndIncrement()

            override suspend fun getSymbolsWithOpenOrders(): Set<Symbol> = emptySet()

            override fun consumeFillPrice(orderId: Int): java.math.BigDecimal? = null
        }
    }

    private open inner class InMemoryBullPutSpreadPort : BullPutSpreadPort {
        private val store = mutableListOf<BullPutSpread>()

        override suspend fun save(spread: BullPutSpread): BullPutSpread {
            val persisted = if (spread.id == null) spread.copy(id = UUID.randomUUID()) else spread
            store.add(persisted)
            return persisted
        }

        override suspend fun update(spread: BullPutSpread): BullPutSpread {
            val idx = store.indexOfFirst { it.id == spread.id }
            require(idx >= 0) { "Not found: ${spread.id}" }
            store[idx] = spread
            return spread
        }

        override suspend fun findById(id: UUID): BullPutSpread? = store.firstOrNull { it.id == id }

        override suspend fun findOpen(): List<BullPutSpread> = store.filter { it.status == SpreadStatus.OPEN }

        override suspend fun findAll(): List<BullPutSpread> = store.toList()

        override suspend fun findPage(
            status: SpreadStatus?,
            page: Int,
            size: Int,
        ): cz.solvina.options.domain.features.spread.SpreadPage {
            val filtered = if (status == null) store.toList() else store.filter { it.status == status }
            val paged = filtered.drop(page * size).take(size)
            return cz.solvina.options.domain.features.spread.SpreadPage(
                paged,
                filtered.size.toLong(),
                (filtered.size + size - 1) / size,
                page,
                size,
            )
        }

        override suspend fun countByStatus(status: SpreadStatus): Long = store.count { it.status == status }.toLong()

        override suspend fun countFilledSince(since: java.time.Instant): Long =
            store.count { it.openedAt >= since && it.status !in SpreadStatus.NOT_FILLED }.toLong()

        override suspend fun findByStatus(status: SpreadStatus): List<BullPutSpread> = store.filter { it.status == status }

        override suspend fun findBySymbolWithLock(symbol: Symbol): List<BullPutSpread> = store.filter { it.symbol == symbol }
    }

    private fun buildOpenSpread(): BullPutSpread =
        BullPutSpread(
            id = UUID.randomUUID(),
            symbol = symbol,
            soldLeg =
                SpreadLeg(
                    contract = soldContract,
                    action = LegAction.SELL,
                    premium = Money(BigDecimal("1.00")),
                    orderId = 1,
                ),
            boughtLeg =
                SpreadLeg(
                    contract = boughtContract,
                    action = LegAction.BUY,
                    premium = Money(BigDecimal("0.50")),
                    orderId = 2,
                ),
            creditPerShare = BigDecimal("0.50"),
            maxRiskPerShare = BigDecimal("4.50"),
            status = SpreadStatus.OPEN,
            ivRankAtEntry = 35.0,
            underlyingPriceAtEntry = BigDecimal("500"),
            openedAt = java.time.Instant.now(),
        )

    // -------------------------------------------------------------------------
    // Fee deduction
    // -------------------------------------------------------------------------

    @Test
    fun `net credit deducts IBKR fees for both legs from the gross fill price`() =
        runTest {
            // IBKR charges $0.65 per contract. A spread has 2 legs → 2 × $0.65 / 100 = $0.013 per share.
            // Gross fill at $1.00 → net credit = $1.00 − $0.013 = $0.987.
            val fillChannel = Channel<OrderStatus>(1)
            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, tickFlowAtCredit(1.00)),
                    orderExecutionPort =
                        immediateComboOrderPort(fillChannel) { _, _, _ ->
                            fillChannel.send(OrderStatus.FILLED)
                        },
                    config = baseConfig.copy(feePerContract = BigDecimal("0.65")),
                )

            val result = service.execute(buildRequest(targetCredit = BigDecimal("1.00")))

            assertEquals(ExecutionOutcome.FILLED, result.outcome)
            assertEquals(
                0,
                BigDecimal("0.9870").compareTo(result.creditAchieved),
                "Net credit must be gross($1.00) − fees(2×$0.65/100=$0.013) = $0.987",
            )
        }

    @Test
    fun `uses the broker-reported fill price over the ladder limit when it's within the expected range`() =
        runTest {
            // Submitted (ladder) limit is $1.00, but the broker reports a better fill of $1.02 — price
            // improvement that must be recorded, or TP/SL thresholds later drift off the wrong credit.
            val fillChannel = Channel<OrderStatus>(1)
            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, tickFlowAtCredit(1.00)),
                    orderExecutionPort =
                        reportedFillOrderPort(fillChannel, reportedFillPrice = BigDecimal("1.02")) { _, _, _ ->
                            fillChannel.send(OrderStatus.FILLED)
                        },
                )

            val result = service.execute(buildRequest(targetCredit = BigDecimal("1.00")))

            assertEquals(ExecutionOutcome.FILLED, result.outcome)
            assertEquals(0, BigDecimal("1.0200").compareTo(result.creditAchieved), "must use the reported $1.02 fill, not the $1.00 limit")
        }

    @Test
    fun `falls back to the ladder limit when the broker-reported fill price is out of the expected range`() =
        runTest {
            // A garbage/mis-signed reported price (way above target+0.10) must not be trusted —
            // fall back to the last ladder limit we know is correct.
            val fillChannel = Channel<OrderStatus>(1)
            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, tickFlowAtCredit(1.00)),
                    orderExecutionPort =
                        reportedFillOrderPort(fillChannel, reportedFillPrice = BigDecimal("99.00")) { _, _, _ ->
                            fillChannel.send(OrderStatus.FILLED)
                        },
                )

            val result = service.execute(buildRequest(targetCredit = BigDecimal("1.00")))

            assertEquals(ExecutionOutcome.FILLED, result.outcome)
            assertEquals(0, BigDecimal("1.0000").compareTo(result.creditAchieved), "must fall back to the $1.00 ladder limit")
        }

    // -------------------------------------------------------------------------
    // Entry cooldown
    // -------------------------------------------------------------------------

    @Test
    fun `blockEntry makes the symbol ineligible for new entries until the duration expires`() =
        runTest {
            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, flow {}),
                    orderExecutionPort = neverFillOrderPort(),
                )

            assertFalse(service.isCoolingDown(symbol), "Symbol should be eligible before any block")
            service.blockEntry(symbol, java.time.Duration.ofHours(2))
            assertTrue(service.isCoolingDown(symbol), "Symbol must be ineligible while the block is active")
        }

    @Test
    fun `entry cooldown is applied after a timed-out execution`() =
        runTest {
            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, tickFlowAtCredit(1.00)),
                    orderExecutionPort = neverFillOrderPort(),
                    config = baseConfig.copy(executionTimeoutMinutes = 1, entryCooldownMinutes = 60),
                )

            assertFalse(service.isCoolingDown(symbol))

            val resultDeferred = backgroundScope.async { service.execute(buildRequest()) }
            advanceTimeBy(baseConfig.executionTimeoutMinutes * 60_000 + 1)
            val result = resultDeferred.await()

            assertEquals(ExecutionOutcome.TIMED_OUT, result.outcome)
            assertTrue(service.isCoolingDown(symbol), "Symbol must be blocked after a timeout to avoid hammering the same strike")
        }

    @Test
    fun `entry cooldown is applied after the credit floor is reached`() =
        runTest {
            // Emit constant ticks at floor price to trigger ladder immediately to floor
            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, tickFlowLaddering(listOf(0.60, 0.60, 0.60, 0.60))),
                    orderExecutionPort = neverFillOrderPort(),
                    config =
                        baseConfig.copy(
                            executionTimeoutMinutes = 5,
                            entryCooldownMinutes = 60,
                            ticksBeforePriceAdjust = 1,
                        ),
                )

            assertFalse(service.isCoolingDown(symbol))

            val result =
                service.execute(
                    buildRequest(targetCredit = BigDecimal("0.60"), floorCredit = BigDecimal("0.50")),
                )

            assertEquals(ExecutionOutcome.FLOOR_REACHED, result.outcome)
            assertTrue(service.isCoolingDown(symbol), "Symbol must be blocked after the credit floor is reached")
        }

    @Test
    fun `quote_freshness_timeout_aborts_entry`() =
        runTest {
            val service =
                buildService(
                    marketTickPort =
                        fixedMarketTickPort(
                            underlyingPrice = 500.0,
                            // Flow that never emits — triggers the 3s freshness timeout
                            creditFlow = flow { delay(Long.MAX_VALUE) },
                        ),
                    orderExecutionPort = neverFillOrderPort(),
                )

            val resultDeferred = backgroundScope.async { service.execute(buildRequest()) }
            // Advance past freshness delay (500ms) + freshness timeout (3000ms)
            advanceTimeBy(4_000)
            val result = resultDeferred.await()

            // No tick within the freshness window = market-data starvation, now distinctly labelled
            // NO_MARKET_DATA (not MARKET_MOVED_TOO_FAR, which is reserved for genuine price drift).
            assertEquals(ExecutionOutcome.NO_MARKET_DATA, result.outcome)
        }

    // -------------------------------------------------------------------------
    // Fresh-credit drift guard scales with target credit
    // -------------------------------------------------------------------------

    @Test
    fun `fresh-credit drift guard scales with target credit - 15pct move on a $1 credit does not abort`() =
        runTest {
            // driftThreshold = max($1.00 × 0.20, $0.05) = $0.20. A flat $0.10 threshold (the old rule)
            // would have aborted this $0.15 move; the percentage-based one must let it through.
            val fillChannel = Channel<OrderStatus>(1)
            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, tickFlowAtCredit(targetCredit = 1.00, soldMidOffset = 0.15)),
                    orderExecutionPort =
                        immediateComboOrderPort(fillChannel) { _, _, _ ->
                            fillChannel.send(OrderStatus.FILLED)
                        },
                )

            val result = service.execute(buildRequest(targetCredit = BigDecimal("1.00")))

            assertEquals(ExecutionOutcome.FILLED, result.outcome, "a 15% drift on a $1.00 credit must not trip the 20% guard")
        }

    @Test
    fun `fresh-credit drift guard still aborts when the move exceeds the percentage threshold`() =
        runTest {
            // driftThreshold = max($1.00 × 0.20, $0.05) = $0.20. A $0.30 move must still abort.
            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, tickFlowAtCredit(targetCredit = 1.00, soldMidOffset = 0.30)),
                    orderExecutionPort = neverFillOrderPort(),
                )

            val result = service.execute(buildRequest(targetCredit = BigDecimal("1.00")))

            assertEquals(ExecutionOutcome.MARKET_MOVED_TOO_FAR, result.outcome)
        }

    @Test
    fun `fresh-credit drift guard floors the threshold at 5 cents for small target credits`() =
        runTest {
            // driftThreshold = max($0.35 × 0.20, $0.05) = $0.07; a $0.10 move must still abort.
            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, tickFlowAtCredit(targetCredit = 0.35, soldMidOffset = 0.10)),
                    orderExecutionPort = neverFillOrderPort(),
                )

            val result = service.execute(buildRequest(targetCredit = BigDecimal("0.35"), floorCredit = BigDecimal("0.20")))

            assertEquals(ExecutionOutcome.MARKET_MOVED_TOO_FAR, result.outcome)
        }

    // -------------------------------------------------------------------------
    // Test Helpers
    // -------------------------------------------------------------------------

    private fun tickFlowAtCredit(
        targetCredit: Double,
        soldMidOffset: Double = 0.0,
        boughtMidOffset: Double = 0.0,
    ): Flow<SpreadCreditTick> {
        val soldMid = targetCredit + 0.50 + soldMidOffset
        val boughtMid = 0.50 + boughtMidOffset
        return flow {
            emit(
                SpreadCreditTick(
                    soldBid = soldMid,
                    soldAsk = soldMid,
                    boughtBid = boughtMid,
                    boughtAsk = boughtMid,
                    netCredit = soldMid - boughtMid,
                ),
            )
        }
    }

    /**
     * Like [tickFlowAtCredit] but mirrors the production credit stream (a callbackFlow): it emits
     * one usable tick and then never completes. Used to exercise E1, where calculateFreshCredit
     * must break after the first tick instead of letting the freshness timeout fire.
     */
    private fun hotTickFlowAtCredit(targetCredit: Double): Flow<SpreadCreditTick> {
        val soldMid = targetCredit + 0.50
        val boughtMid = 0.50
        return flow {
            emit(
                SpreadCreditTick(
                    soldBid = soldMid,
                    soldAsk = soldMid,
                    boughtBid = boughtMid,
                    boughtAsk = boughtMid,
                    netCredit = soldMid - boughtMid,
                ),
            )
            awaitCancellation()
        }
    }

    private fun underlyingPriceFlowWithDrift(
        startPrice: Double,
        driftPct: Double,
    ): Flow<Double> =
        flow {
            emit(startPrice)
            delay(100)
            val driftedPrice = startPrice * (1 + driftPct)
            emit(driftedPrice)
        }

    private fun tickFlowLaddering(
        prices: List<Double>,
        delayMs: Long = 100,
    ): Flow<SpreadCreditTick> =
        flow {
            for (credit in prices) {
                val soldMid = credit + 0.50
                val boughtMid = 0.50
                emit(
                    SpreadCreditTick(
                        soldBid = soldMid,
                        soldAsk = soldMid,
                        boughtBid = boughtMid,
                        boughtAsk = boughtMid,
                        netCredit = credit,
                    ),
                )
                if (prices.indexOf(credit) < prices.size - 1) delay(delayMs)
            }
        }
}
