package cz.solvina.options.execution

import cz.solvina.options.domain.features.account.AccountDetail
import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.execution.TradeExecutionService
import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.MarketTickPort
import cz.solvina.options.domain.features.market.SpreadCreditTick
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
            driftProtectionPct = 0.02,
            floorCreditBuffer = 0.50,
            executionTimeoutMinutes = 1,
            ticksBeforePriceAdjust = 3,
            maxLegBidAskSpreadPct = 0.30,
        )

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
        spreadPort: SpreadPort = InMemorySpreadPort(),
        accountPort: AccountPort = buildAccountPort(),
        config: ScannerConfig = baseConfig,
    ) = TradeExecutionService(
        marketTickPort = marketTickPort,
        orderExecutionPort = orderExecutionPort,
        spreadPort = spreadPort,
        accountPort = accountPort,
        config = config,
        clock = Clock.systemUTC(),
        scope = backgroundScope,
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `fills_immediately_at_target_credit`() =
        runTest {
            val fillChannel = Channel<OrderStatus>(1)

            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, flow {}),
                    orderExecutionPort =
                        immediateComboOrderPort(fillChannel) { _, _, _ ->
                            fillChannel.send(OrderStatus.FILLED)
                        },
                )

            val result = service.execute(buildRequest(targetCredit = BigDecimal("1.00")))

            assertEquals(ExecutionOutcome.FILLED, result.outcome)
            assertEquals(BigDecimal("1.00"), result.creditAchieved)
            assertNotNull(result.comboOrderId)
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
                    ): Int {
                        val id = nextId.getAndIncrement()
                        val deferred = CompletableDeferred<OrderStatus>()
                        deferreds[id] = deferred
                        val n = submitCount.incrementAndGet()
                        if (n == 2) deferred.complete(OrderStatus.FILLED)
                        return id
                    }

                    override suspend fun awaitFill(orderId: Int): OrderStatus = deferreds[orderId]?.await() ?: OrderStatus.CANCELLED

                    override suspend fun cancelAndAwait(orderId: Int) {
                        deferreds[orderId]?.complete(OrderStatus.CANCELLED)
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
                }

            val creditChannel = Channel<SpreadCreditTick>(Channel.UNLIMITED)

            val service =
                buildService(
                    marketTickPort =
                        fixedMarketTickPort(
                            underlyingPrice = 500.0,
                            creditFlow = creditChannel.consumeAsFlow(),
                        ),
                    orderExecutionPort = orderPort,
                    config = baseConfig.copy(ticksBeforePriceAdjust = 3),
                )

            launch {
                repeat(5) {
                    creditChannel.send(SpreadCreditTick(0.95, 1.05, 0.45, 0.55, 0.55))
                    yield()
                }
            }

            val result = service.execute(buildRequest(targetCredit = BigDecimal("1.00")))

            assertEquals(ExecutionOutcome.FILLED, result.outcome)
            assertTrue(result.creditAchieved!! < BigDecimal("1.00"), "Expected laddered credit below target")
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
                    ): Int {
                        submittedCredits.add(netCredit.amount)
                        return nextId.getAndIncrement()
                    }

                    override suspend fun awaitFill(orderId: Int): OrderStatus {
                        delay(Long.MAX_VALUE)
                        return OrderStatus.CANCELLED
                    }

                    override suspend fun cancelAndAwait(orderId: Int) {}

                    override suspend fun replaceComboWithNewPrice(
                        existingOrderId: Int,
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        newCredit: Money,
                        qty: Int,
                    ): Int = submitComboLimitOrder(soldContract, boughtContract, newCredit, qty)
                }

            val creditChannel = Channel<SpreadCreditTick>(Channel.UNLIMITED)

            val service =
                buildService(
                    marketTickPort =
                        fixedMarketTickPort(
                            underlyingPrice = 500.0,
                            creditFlow = creditChannel.consumeAsFlow(),
                        ),
                    orderExecutionPort = orderPort,
                    config = baseConfig.copy(ticksBeforePriceAdjust = 1),
                )

            launch {
                repeat(100) {
                    creditChannel.send(SpreadCreditTick(0.95, 1.05, 0.45, 0.55, 0.55))
                    yield()
                }
            }

            val result =
                service.execute(
                    buildRequest(targetCredit = BigDecimal("1.00"), floorCredit = BigDecimal("0.90")),
                )

            assertEquals(ExecutionOutcome.FLOOR_REACHED, result.outcome)
            assertTrue(
                submittedCredits.all { it >= BigDecimal("0.90") },
                "Order submitted below floor: $submittedCredits",
            )
        }

    @Test
    fun `drift_aborts_execution`() =
        runTest {
            val underlyingChannel = Channel<Double>(Channel.UNLIMITED)

            val service =
                buildService(
                    marketTickPort =
                        object : MarketTickPort {
                            override fun streamUnderlyingPrice(symbol: Symbol): Flow<Double> = underlyingChannel.consumeAsFlow()

                            override fun streamSpreadCredit(
                                soldContract: OptionContract,
                                boughtContract: OptionContract,
                            ): Flow<SpreadCreditTick> = flow {}
                        },
                    orderExecutionPort = neverFillOrderPort(),
                    config = baseConfig.copy(driftProtectionPct = 0.01),
                )

            // Entry at 500; send 510 (2 % drift) which exceeds 1 % threshold
            launch {
                yield()
                underlyingChannel.send(510.0)
            }

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
                    ): Int = 1

                    override suspend fun awaitFill(orderId: Int): OrderStatus {
                        delay(Long.MAX_VALUE)
                        return OrderStatus.CANCELLED
                    }

                    override suspend fun cancelAndAwait(orderId: Int) {
                        cancelCalled = true
                    }

                    override suspend fun replaceComboWithNewPrice(
                        existingOrderId: Int,
                        soldContract: OptionContract,
                        boughtContract: OptionContract,
                        newCredit: Money,
                        qty: Int,
                    ): Int = 1
                }

            val service =
                buildService(
                    marketTickPort = fixedMarketTickPort(500.0, flow {}),
                    orderExecutionPort = orderPort,
                    config = baseConfig.copy(executionTimeoutMinutes = 1),
                )

            launch { advanceTimeBy(61_000) }

            val result = service.execute(buildRequest())

            assertEquals(ExecutionOutcome.TIMED_OUT, result.outcome)
            assertTrue(cancelCalled, "cancelAndAwait should have been called on timeout")
        }

    @Test
    fun `exposure_rejects_duplicate`() =
        runTest {
            val spreadPort =
                object : InMemorySpreadPort() {
                    override suspend fun findOpen() = listOf(buildOpenSpread())
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
            ): Int {
                onSubmit(soldContract, boughtContract, netCredit)
                return nextId.getAndIncrement()
            }

            override suspend fun awaitFill(orderId: Int): OrderStatus = fillChannel.receive()

            override suspend fun cancelAndAwait(orderId: Int) {}

            override suspend fun replaceComboWithNewPrice(
                existingOrderId: Int,
                soldContract: OptionContract,
                boughtContract: OptionContract,
                newCredit: Money,
                qty: Int,
            ): Int = submitComboLimitOrder(soldContract, boughtContract, newCredit, qty)
        }
    }

    private fun neverFillOrderPort(): OrderExecutionPort {
        val nextId = AtomicInteger(1)
        return object : OrderExecutionPort {
            override suspend fun submitComboLimitOrder(
                soldContract: OptionContract,
                boughtContract: OptionContract,
                netCredit: Money,
                qty: Int,
            ): Int = nextId.getAndIncrement()

            override suspend fun awaitFill(orderId: Int): OrderStatus {
                delay(Long.MAX_VALUE)
                return OrderStatus.CANCELLED
            }

            override suspend fun cancelAndAwait(orderId: Int) {}

            override suspend fun replaceComboWithNewPrice(
                existingOrderId: Int,
                soldContract: OptionContract,
                boughtContract: OptionContract,
                newCredit: Money,
                qty: Int,
            ): Int = nextId.getAndIncrement()
        }
    }

    private open inner class InMemorySpreadPort : SpreadPort {
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

        override suspend fun countByStatus(status: SpreadStatus): Long = store.count { it.status == status }.toLong()

        override suspend fun findByStatus(status: SpreadStatus): List<BullPutSpread> = store.filter { it.status == status }
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
}
