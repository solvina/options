package cz.solvina.options.scanner

import cz.solvina.options.domain.features.account.AccountDetail
import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.execution.TradeExecutionPort
import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.execution.model.TradeExecutionResult
import cz.solvina.options.domain.features.scanner.BearCallCandidateSelector
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.scanner.BullPutCandidateSelector
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerService
import cz.solvina.options.domain.features.spread.BullPutSpreadPort
import cz.solvina.options.domain.features.spread.SpreadPage
import cz.solvina.options.domain.features.spread.SpreadQueryFacade
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import cz.solvina.options.testutil.InMemoryBearCallSpreadPort
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Scanner orchestration rules:
 *
 *   — Scan is skipped entirely when the total open spread count reaches maxOpenSpreads (default 5).
 *   — A symbol is skipped when it already has an OPEN, PENDING, or CLOSING spread in the DB.
 *   — A symbol is skipped when an entry execution is currently in progress (in-flight).
 *   — A symbol is skipped when it is in the post-failure cooldown period.
 *   — For all other symbols the selector is invoked; a non-null result triggers an async execution.
 */
class ScannerServiceTest {
    // -------------------------------------------------------------------------
    // Shared fixtures
    // -------------------------------------------------------------------------

    private val spy = Symbol("SPY")
    private val qqq = Symbol("QQQ")
    private val expiry = LocalDate.of(2025, 3, 21)
    private val config = ScannerConfig(watchlist = listOf("SPY", "QQQ"), maxOpenSpreads = 2)

    // Candidate selector is mocked because its own filter pipeline is tested separately
    // (see BullPutCandidateSelectorTest). Here we only need to control whether it returns a result.
    private val selector = mockk<BullPutCandidateSelector>()

    private val dummyRequest =
        TradeExecutionRequest(
            soldContract = OptionContract(spy, expiry, BigDecimal("480"), OptionType.PUT),
            boughtContract = OptionContract(spy, expiry, BigDecimal("475"), OptionType.PUT),
            underlyingSymbol = spy,
            targetCredit = BigDecimal("0.80"),
            floorCredit = BigDecimal("0.40"),
            maxRiskPerShare = BigDecimal("4.20"),
            ivRankAtEntry = 35.0,
            soldBid = BigDecimal("1.40"),
            soldAsk = BigDecimal("1.60"),
            boughtBid = BigDecimal("0.65"),
            boughtAsk = BigDecimal("0.75"),
            boughtMid = BigDecimal("0.70"),
            underlyingPriceAtEntry = BigDecimal("500"),
        )

    // -------------------------------------------------------------------------
    // maxOpenSpreads gate
    // -------------------------------------------------------------------------

    @Test
    fun `scan is skipped entirely when max open spreads is reached`() =
        runTest {
            // With 2 spreads open and maxOpenSpreads=2 there is no room for another trade.
            // The selector must not be called at all — no point evaluating candidates.
            val spreadPort = BullPutSpreadPortStub(openCount = 2)

            buildScanner(spreadPort = spreadPort).scan()

            coVerify(exactly = 0) { selector.select(any(), any()) }
        }

    // -------------------------------------------------------------------------
    // Per-symbol duplicate-position gates
    // -------------------------------------------------------------------------

    @Test
    fun `symbol is skipped when it already has an open spread`() =
        runTest {
            val spreadPort = BullPutSpreadPortStub(openSpreads = listOf(openSpread(spy)))

            buildScanner(spreadPort = spreadPort, watchlist = listOf(spy)).scan()

            coVerify(exactly = 0) { selector.select(spy, any()) }
        }

    @Test
    fun `symbol is skipped when it has a spread in CLOSING state`() =
        runTest {
            // A CLOSING spread means an exit order is in flight. Entering again would duplicate.
            val spreadPort = BullPutSpreadPortStub(closingSpreads = listOf(openSpread(spy, status = SpreadStatus.CLOSING)))

            buildScanner(spreadPort = spreadPort, watchlist = listOf(spy)).scan()

            coVerify(exactly = 0) { selector.select(spy, any()) }
        }

    @Test
    fun `symbol is skipped when it has a PENDING spread waiting for entry fill`() =
        runTest {
            val spreadPort = BullPutSpreadPortStub(pendingSpreads = listOf(openSpread(spy, status = SpreadStatus.PENDING)))

            buildScanner(spreadPort = spreadPort, watchlist = listOf(spy)).scan()

            coVerify(exactly = 0) { selector.select(spy, any()) }
        }

    // -------------------------------------------------------------------------
    // In-flight and cooldown gates
    // -------------------------------------------------------------------------

    @Test
    fun `symbol is skipped when an entry execution is already in progress`() =
        runTest {
            // isInFlight protects against launching two executions for the same symbol
            // concurrently — the in-memory map is the guard between DB write and fill confirmation.
            val executionPort = FakeExecutionPort(inFlight = setOf(spy))

            buildScanner(executionPort = executionPort, watchlist = listOf(spy)).scan()

            coVerify(exactly = 0) { selector.select(spy, any()) }
        }

    @Test
    fun `symbol is skipped while in the post-failure entry cooldown`() =
        runTest {
            // After a timed-out or aborted entry the scanner must wait entryCooldownMinutes
            // before retrying, so we do not hammer the same symbol repeatedly.
            val executionPort = FakeExecutionPort(coolingDown = setOf(spy))

            buildScanner(executionPort = executionPort, watchlist = listOf(spy)).scan()

            coVerify(exactly = 0) { selector.select(spy, any()) }
        }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    fun `eligible symbol is evaluated by the selector and submitted for execution`() =
        runTest {
            // SPY passes all gates; selector returns a valid request; execution is launched.
            val executionPort = FakeExecutionPort()
            // Build scanner first (it registers a default null answer), then override for SPY.
            val scanner = buildScanner(executionPort = executionPort, watchlist = listOf(spy))
            coEvery { selector.select(spy, any()) } returns dummyRequest

            scanner.scan()

            coVerify(exactly = 1) { selector.select(spy, any()) }
            // Thread.sleep gives the Dispatchers.IO background coroutine time to record the call.
            Thread.sleep(500)
            assertEquals(1, executionPort.executions.size)
            assertEquals(spy, executionPort.executions.first().underlyingSymbol)
        }

    // -------------------------------------------------------------------------
    // Multi-strategy: bear call
    // -------------------------------------------------------------------------

    @Test
    fun `bear call is scanned when enabled and bull put yields no candidate`() =
        runTest {
            val executionPort = FakeExecutionPort()
            val bearSelector = mockk<BearCallCandidateSelector>()
            val scanner =
                buildScanner(
                    executionPort = executionPort,
                    watchlist = listOf(spy),
                    bearCallSelector = bearSelector,
                    bearCallConfig = BearCallScannerConfig(enabled = true),
                )
            coEvery { selector.select(spy, any()) } returns null
            coEvery { bearSelector.select(spy, any()) } returns dummyRequest

            scanner.scan()

            coVerify(exactly = 1) { bearSelector.select(spy, any()) }
            Thread.sleep(500)
            assertEquals(1, executionPort.executions.size)
        }

    @Test
    fun `bear call selector is not called when bear call is disabled`() =
        runTest {
            val bearSelector = mockk<BearCallCandidateSelector>(relaxed = true)
            val scanner =
                buildScanner(
                    watchlist = listOf(spy),
                    bearCallSelector = bearSelector,
                    bearCallConfig = BearCallScannerConfig(enabled = false),
                )
            coEvery { selector.select(spy, any()) } returns null

            scanner.scan()

            coVerify(exactly = 0) { bearSelector.select(any(), any()) }
        }

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    private fun buildScanner(
        spreadPort: BullPutSpreadPort = BullPutSpreadPortStub(),
        executionPort: TradeExecutionPort = FakeExecutionPort(),
        watchlist: List<Symbol> = listOf(spy, qqq),
        bearCallSelector: BearCallCandidateSelector = mockk(relaxed = true),
        bearCallConfig: BearCallScannerConfig = BearCallScannerConfig(),
        scope: CoroutineScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
    ): ScannerService {
        val universePort =
            object : UniversePort {
                override fun getWatchlist() = watchlist

                override fun getFlagWatchlist() = emptyList<Symbol>()

                override fun getActiveSymbols() = watchlist

                override fun isMarketOpen(symbol: Symbol) = true

                override fun getMarketSchedule(symbol: Symbol) =
                    cz.solvina.options.domain.features.universe.MarketSchedule(
                        zone = java.time.ZoneId.of("America/New_York"),
                        open = java.time.LocalTime.of(9, 30),
                        close = java.time.LocalTime.of(16, 0),
                        session = "US",
                    )

                override suspend fun getAll() = emptyList<InstrumentConfig>()

                override suspend fun get(symbol: Symbol) = null

                override suspend fun save(config: InstrumentConfig) = config

                override suspend fun delete(symbol: Symbol) {}
            }

        val accountPort =
            object : AccountPort {
                override val accountDetail =
                    MutableStateFlow(
                        AccountDetail(
                            totalCapital = Money(BigDecimal("50000")),
                            availableFunds = Money(BigDecimal("40000")),
                        ),
                    )
            }

        // Default: selector returns no candidate (symbol is skipped after evaluation)
        coEvery { selector.select(any(), any()) } returns null

        return ScannerService(
            universePort = universePort,
            bullPutSelector = selector,
            bearCallSelector = bearCallSelector,
            bearCallConfig = bearCallConfig,
            accountPort = accountPort,
            executionPort = executionPort,
            spreadQuery = SpreadQueryFacade(spreadPort, InMemoryBearCallSpreadPort()),
            config = config,
            clock = Clock.systemUTC(),
            scope = scope,
        )
    }

    private fun openSpread(
        symbol: Symbol,
        status: SpreadStatus = SpreadStatus.OPEN,
    ) = BullPutSpread(
        id = UUID.randomUUID(),
        symbol = symbol,
        soldLeg =
            SpreadLeg(
                OptionContract(symbol, expiry, BigDecimal("480"), OptionType.PUT),
                cz.solvina.options.domain.features.order.LegAction.SELL,
                Money(BigDecimal("1.50")),
                orderId = 1,
            ),
        boughtLeg =
            SpreadLeg(
                OptionContract(symbol, expiry, BigDecimal("475"), OptionType.PUT),
                cz.solvina.options.domain.features.order.LegAction.BUY,
                Money(BigDecimal("0.70")),
                orderId = 2,
            ),
        creditPerShare = BigDecimal("0.80"),
        maxRiskPerShare = BigDecimal("4.20"),
        status = status,
        ivRankAtEntry = 35.0,
        underlyingPriceAtEntry = BigDecimal("500"),
        openedAt = Instant.now(),
    )

    /**
     * In-memory spread store. Only the methods used by [ScannerService.scan] are implemented.
     */
    private class BullPutSpreadPortStub(
        private val openSpreads: List<BullPutSpread> = emptyList(),
        private val closingSpreads: List<BullPutSpread> = emptyList(),
        private val pendingSpreads: List<BullPutSpread> = emptyList(),
        openCount: Int = -1,
    ) : BullPutSpreadPort {
        private val derivedOpenCount = if (openCount >= 0) openCount.toLong() else openSpreads.size.toLong()

        override suspend fun findOpen() = openSpreads

        override suspend fun findByStatus(status: SpreadStatus) =
            when (status) {
                SpreadStatus.OPEN -> openSpreads
                SpreadStatus.CLOSING -> closingSpreads
                SpreadStatus.PENDING -> pendingSpreads
                else -> emptyList()
            }

        override suspend fun countByStatus(status: SpreadStatus) =
            when (status) {
                SpreadStatus.OPEN -> derivedOpenCount
                SpreadStatus.CLOSING -> closingSpreads.size.toLong()
                SpreadStatus.PENDING -> pendingSpreads.size.toLong()
                else -> 0L
            }

        override suspend fun save(spread: BullPutSpread) = spread

        override suspend fun update(spread: BullPutSpread) = spread

        override suspend fun findById(id: UUID) = null

        override suspend fun findAll() = emptyList<BullPutSpread>()

        override suspend fun findPage(
            status: SpreadStatus?,
            page: Int,
            size: Int,
        ) = SpreadPage(emptyList(), 0, 0, page, size)

        override suspend fun findBySymbolWithLock(symbol: Symbol) = emptyList<BullPutSpread>()
    }

    /**
     * Fake execution port whose [isInFlight] and [isCoolingDown] responses are configurable.
     * Records [execute] calls so tests can assert whether an execution was launched.
     */
    private class FakeExecutionPort(
        private val inFlight: Set<Symbol> = emptySet(),
        private val coolingDown: Set<Symbol> = emptySet(),
    ) : TradeExecutionPort {
        val executions = mutableListOf<TradeExecutionRequest>()

        override suspend fun execute(request: TradeExecutionRequest): TradeExecutionResult {
            executions.add(request)
            return TradeExecutionResult(ExecutionOutcome.FILLED)
        }

        override fun isInFlight(symbol: Symbol) = symbol in inFlight

        override fun isCoolingDown(symbol: Symbol) = symbol in coolingDown

        override fun blockEntry(
            symbol: Symbol,
            duration: java.time.Duration,
        ) {}
    }
}
