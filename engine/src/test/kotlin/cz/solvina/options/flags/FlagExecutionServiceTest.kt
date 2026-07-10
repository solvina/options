package cz.solvina.options.flags

import cz.solvina.options.domain.features.account.AccountDetail
import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.account.EffectiveAccountService
import cz.solvina.options.domain.features.flag.BracketOrderIds
import cz.solvina.options.domain.features.flag.BracketOrderPort
import cz.solvina.options.domain.features.flag.OrderFill
import cz.solvina.options.domain.features.flag.FlagExecutionService
import cz.solvina.options.domain.features.flag.FlagPage
import cz.solvina.options.domain.features.flag.FlagPort
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Execution rules for bull-flag bracket orders:
 *
 *   Position sizing  — shares = floor(riskPerTrade / (entryPrice − stopLossPrice)).
 *                      Minimum 1 share so a position is always attempted.
 *
 *   Profit target    — entry + 2 × (entry − stop). Fixed 2R target.
 *
 *   Entry slippage   — actualFillPrice − entryPrice. Negative means filled better than expected.
 *                      Recorded on the position for trade-quality analysis.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlagExecutionServiceTest {
    private val symbol = Symbol("AAPL")
    private val fixedClock = Clock.fixed(Instant.parse("2025-06-05T14:00:00Z"), ZoneOffset.UTC)

    // -------------------------------------------------------------------------
    // Position sizing
    // -------------------------------------------------------------------------

    @Test
    fun `shares equal floor of riskPerTrade divided by stop distance`() =
        runTest {
            // riskPerTrade=$100, entry=$10.00, stop=$9.50 → risk/share=$0.50 → 100/0.50 = 200 shares.
            val flagPort = CapturingFlagPort()
            val service = buildService(flagPort = flagPort)

            service.execute(
                buildRequest(
                    entryPrice = BigDecimal("10.00"),
                    stopLossPrice = BigDecimal("9.50"),
                    riskPerTrade = BigDecimal("100.00"),
                ),
            )

            val saved = flagPort.saved.first()
            assertEquals(200, saved.shares)
        }

    @Test
    fun `notional cap limits shares below risk-based size when the stop is tight`() =
        runTest {
            // Risk-based sizing would buy 200 shares ($2,000 notional), but on a $4,000 account the
            // 25% notional cap = $1,000 → 100 shares. This is the go-live protection: a tight stop must
            // not let one position sweep the book / blow past buying power.
            val flagPort = CapturingFlagPort()
            val service = buildService(flagPort = flagPort, accountPort = stubAccountPort(BigDecimal("4000")))

            service.execute(
                buildRequest(
                    entryPrice = BigDecimal("10.00"),
                    stopLossPrice = BigDecimal("9.50"),
                    riskPerTrade = BigDecimal("100.00"),
                ),
            )

            val saved = flagPort.saved.first()
            assertEquals(100, saved.shares, "Notional cap (25% × \$4,000 ÷ \$10) must cap 200 risk-based shares to 100")
        }

    @Test
    fun `minimum of 1 share is enforced when calculated size rounds down to zero`() =
        runTest {
            // riskPerTrade=$0.01, entry=$100.00, stop=$50.00 → risk/share=$50 → floor(0.01/50) = 0 → clamped to 1.
            val flagPort = CapturingFlagPort()
            val service = buildService(flagPort = flagPort)

            service.execute(
                buildRequest(
                    entryPrice = BigDecimal("100.00"),
                    stopLossPrice = BigDecimal("50.00"),
                    riskPerTrade = BigDecimal("0.01"),
                ),
            )

            val saved = flagPort.saved.first()
            assertEquals(1, saved.shares, "Minimum 1 share prevents a zero-size order submission")
        }

    // -------------------------------------------------------------------------
    // Profit target calculation
    // -------------------------------------------------------------------------

    @Test
    fun `profit target is entry plus two times the risk per share (2R)`() =
        runTest {
            // entry=$10.00, stop=$9.00 → risk=$1.00 → target = $10.00 + 2×$1.00 = $12.00
            val flagPort = CapturingFlagPort()
            val service = buildService(flagPort = flagPort)

            service.execute(
                buildRequest(
                    entryPrice = BigDecimal("10.00"),
                    stopLossPrice = BigDecimal("9.00"),
                    riskPerTrade = BigDecimal("100.00"),
                ),
            )

            val saved = flagPort.saved.first()
            assertEquals(
                0,
                BigDecimal("12.00").compareTo(saved.profitTargetPrice),
                "Profit target = entry + 2 × (entry − stop) = 10 + 2 = 12",
            )
        }

    // -------------------------------------------------------------------------
    // Entry slippage tracking
    // -------------------------------------------------------------------------

    @Test
    fun `entry slippage is recorded when the actual fill price differs from the signal price`() {
        // Signal price: $10.00 (stop trigger). Actual fill: $10.03 (gap-up or fast market).
        // Slippage = $10.03 − $10.00 = $0.03 (positive = paid more than expected).
        // Uses runBlocking + Thread.sleep because the fill-await coroutine runs on GlobalScope
        // (a real thread pool), and virtual-time test schedulers don't control it.
        val actualFill = BigDecimal("10.03")
        val flagPort = CapturingFlagPort()
        val bracketPort = ImmediateFillBracketPort(entryFillPrice = actualFill)
        val service = buildService(flagPort = flagPort, bracketPort = bracketPort)

        kotlinx.coroutines.runBlocking {
            service.execute(
                buildRequest(
                    entryPrice = BigDecimal("10.00"),
                    stopLossPrice = BigDecimal("9.00"),
                ),
            )
        }

        Thread.sleep(500)

        val openPosition = flagPort.saved.firstOrNull { it.status == FlagStatus.OPEN }
        assertNotNull(openPosition, "Position should transition to OPEN after entry fill")
        assertEquals(
            0,
            BigDecimal("0.03").compareTo(openPosition.entrySlippage),
            "Entry slippage = actualFill − signalPrice",
        )
    }

    // -------------------------------------------------------------------------
    // Exit booking — the close must reflect the ACTUAL trail fill, not a theoretical price
    // -------------------------------------------------------------------------

    @Test
    fun `trail fill above entry books CLOSED_PROFIT at the actual fill price`() {
        // Entry $10.00, trail fill reported at $13.50 — well past the nominal 2R "target" of $12.
        // The old code booked $12.00 (profitTargetPrice); the close must carry the real $13.50.
        val flagPort = CapturingFlagPort()
        val service = buildService(flagPort = flagPort, bracketPort = trailFillPort(OrderFill(OrderStatus.FILLED, BigDecimal("13.50"))))
        val open = openTrailPosition(flagPort)

        service.launchExitWatch(open)
        Thread.sleep(500)

        val closed = flagPort.saved.last()
        assertEquals(FlagStatus.CLOSED_PROFIT, closed.status)
        assertEquals("trail_exit_profit", closed.closeReason)
        assertEquals(0, BigDecimal("13.50").compareTo(closed.closePriceActual))
        assertEquals(0, BigDecimal("350.00").compareTo(closed.realizedPnl), "pnl = (13.50 − 10.00) × 100 shares")
    }

    @Test
    fun `trail fill below entry books CLOSED_STOP with a loss even though it is the same order id`() {
        // The single TRAIL order exits winners and losers alike — a fill at $9.20 is a stop-out,
        // regardless of the order id being stored as both stopLossOrderId and profitTargetOrderId.
        val flagPort = CapturingFlagPort()
        val service = buildService(flagPort = flagPort, bracketPort = trailFillPort(OrderFill(OrderStatus.FILLED, BigDecimal("9.20"))))
        val open = openTrailPosition(flagPort)

        service.launchExitWatch(open)
        Thread.sleep(500)

        val closed = flagPort.saved.last()
        assertEquals(FlagStatus.CLOSED_STOP, closed.status)
        assertEquals("trail_exit_loss", closed.closeReason)
        assertEquals(0, BigDecimal("9.20").compareTo(closed.closePriceActual))
        assertEquals(0, BigDecimal("-80.00").compareTo(closed.realizedPnl), "pnl = (9.20 − 10.00) × 100 shares")
    }

    @Test
    fun `trail fill without a reported price books the ratcheted trigger estimate`() {
        // No avgFillPrice from the broker: best estimate is highestSeen − trail = 14.00 − 2.00 = 12.00
        // (above the initial stop, so the ratchet wins), flagged as estimated in the reason.
        val flagPort = CapturingFlagPort()
        val service = buildService(flagPort = flagPort, bracketPort = trailFillPort(OrderFill(OrderStatus.FILLED, avgPrice = null)))
        val open = openTrailPosition(flagPort, highestPriceSeen = BigDecimal("14.00"))

        service.launchExitWatch(open)
        Thread.sleep(500)

        val closed = flagPort.saved.last()
        assertEquals(FlagStatus.CLOSED_PROFIT, closed.status)
        assertEquals("trail_exit_profit_estimated", closed.closeReason)
        assertEquals(0, BigDecimal("12.00").compareTo(closed.closePriceActual))
    }

    @Test
    fun `a single trail order id arms exactly one watcher and books exactly one close`() {
        // stopLossOrderId == profitTargetOrderId (one TRAIL order): the old code launched two
        // watchers on it, racing to book two different theoretical closes.
        val flagPort = CapturingFlagPort()
        val bracketPort = trailFillPort(OrderFill(OrderStatus.FILLED, BigDecimal("11.00")))
        val service = buildService(flagPort = flagPort, bracketPort = bracketPort)
        val open = openTrailPosition(flagPort)

        service.launchExitWatch(open)
        Thread.sleep(500)

        assertEquals(1, bracketPort.childAwaits.size, "One distinct exit order id → one watcher")
        assertEquals(1, flagPort.saved.count { it.status == FlagStatus.CLOSED_PROFIT || it.status == FlagStatus.CLOSED_STOP })
    }

    /** OPEN trail-era position: entry $10, stop $9, trail $2, 100 shares, one exit order id (42). */
    private fun openTrailPosition(
        flagPort: CapturingFlagPort,
        highestPriceSeen: BigDecimal? = null,
    ): FlagPosition {
        val position =
            FlagPosition(
                id = UUID.randomUUID(),
                symbol = symbol,
                status = FlagStatus.OPEN,
                entryOrderId = 41,
                stopLossOrderId = 42,
                profitTargetOrderId = 42,
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
                highestPriceSeen = highestPriceSeen,
            )
        kotlinx.coroutines.runBlocking { flagPort.save(position) }
        return position
    }

    /** Bracket port whose child watch resolves immediately with [fill], recording each awaited id. */
    private fun trailFillPort(fill: OrderFill) = TrailFillBracketPort(fill)

    private class TrailFillBracketPort(
        private val childFill: OrderFill,
    ) : BracketOrderPort {
        val childAwaits = mutableListOf<Int>()

        override suspend fun submitBracketOrder(
            symbol: Symbol,
            shares: Int,
            entryPrice: BigDecimal,
            stopLossPrice: BigDecimal,
            trailAmount: BigDecimal,
        ) = BracketOrderIds(entryOrderId = 41, stopLossOrderId = 42, profitTargetOrderId = 42)

        override suspend fun cancelOrder(orderId: Int) {}

        override suspend fun awaitParentFill(orderId: Int) = OrderFill(OrderStatus.FILLED)

        override suspend fun rewatchParentFill(orderId: Int) = awaitParentFill(orderId)

        override suspend fun awaitChildFill(orderId: Int): OrderFill {
            childAwaits.add(orderId)
            return childFill
        }

        override suspend fun rewatchChildFill(orderId: Int) = awaitChildFill(orderId)

        override fun hasActiveWatch(orderId: Int) = false

        override suspend fun submitTrailingStopSell(
            symbol: Symbol,
            shares: Int,
            initialStop: BigDecimal,
            trailAmount: BigDecimal,
        ) = 98

        override suspend fun submitMarketSell(
            symbol: Symbol,
            shares: Int,
        ) = 99
    }

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    private fun buildRequest(
        entryPrice: BigDecimal = BigDecimal("10.00"),
        stopLossPrice: BigDecimal = BigDecimal("9.00"),
        riskPerTrade: BigDecimal = BigDecimal("100.00"),
    ) = FlagExecutionService.ExecutionRequest(
        symbol = symbol,
        entryPrice = entryPrice,
        stopLossPrice = stopLossPrice,
        flagpoleHeight = BigDecimal("5.00"),
        flagRetracement = BigDecimal("0.30"),
        resistanceAtEntry = entryPrice,
        patternStartedAt = null,
        signalTime = Instant.parse("2025-06-05T14:00:00Z"),
        tradingConfig = FlagTradingConfig(riskPerTrade = riskPerTrade),
    )

    // Capital large enough that the notional cap never binds — these tests assert risk-based sizing.
    private fun stubAccountPort(capital: BigDecimal = BigDecimal("100000000")): AccountPort =
        object : AccountPort {
            override val accountDetail =
                MutableStateFlow(AccountDetail(totalCapital = Money(capital), availableFunds = Money(capital)))
        }

    private fun buildService(
        flagPort: FlagPort = CapturingFlagPort(),
        bracketPort: BracketOrderPort = ImmediateFillBracketPort(),
        scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.GlobalScope,
        accountPort: AccountPort = stubAccountPort(),
    ) = FlagExecutionService(
        bracketOrderPort = bracketPort,
        flagPort = flagPort,
        clock = fixedClock,
        scope = scope,
        effectiveAccount = EffectiveAccountService(accountPort, null),
        maxPositionPctOfCapital = BigDecimal("0.25"),
    )

    /** Records every [save] call for assertion. */
    private class CapturingFlagPort : FlagPort {
        val saved = mutableListOf<FlagPosition>()

        override suspend fun save(position: FlagPosition) = position.also { saved.add(it) }

        override suspend fun update(position: FlagPosition) = position.also { saved.add(it) }

        override suspend fun findById(id: UUID) = saved.lastOrNull { it.id == id }

        override suspend fun findOpen() = saved.filter { it.status == FlagStatus.OPEN }

        override suspend fun findAll() = saved.toList()

        override suspend fun findByStatus(status: FlagStatus) = saved.filter { it.status == status }

        override suspend fun countByStatus(status: FlagStatus) = saved.count { it.status == status }.toLong()

        override suspend fun findPage(
            status: FlagStatus?,
            page: Int,
            size: Int,
            sort: String,
            sortDir: String,
        ) = FlagPage(emptyList(), 0, 0, page, size)
    }

    /** Bracket port that immediately reports a filled entry at [entryFillPrice]. */
    private class ImmediateFillBracketPort(
        private val entryFillPrice: BigDecimal = BigDecimal("10.00"),
    ) : BracketOrderPort {
        override suspend fun submitBracketOrder(
            symbol: Symbol,
            shares: Int,
            entryPrice: BigDecimal,
            stopLossPrice: BigDecimal,
            trailAmount: BigDecimal,
        ) = BracketOrderIds(entryOrderId = 1, stopLossOrderId = 2, profitTargetOrderId = 3)

        override suspend fun cancelOrder(orderId: Int) {}

        override suspend fun awaitParentFill(orderId: Int) = OrderFill(status = OrderStatus.FILLED, avgPrice = entryFillPrice)

        override suspend fun rewatchParentFill(orderId: Int) = awaitParentFill(orderId)

        override suspend fun rewatchChildFill(orderId: Int): OrderFill = awaitChildFill(orderId)

        override fun hasActiveWatch(orderId: Int) = false

        override suspend fun submitTrailingStopSell(
            symbol: Symbol,
            shares: Int,
            initialStop: BigDecimal,
            trailAmount: BigDecimal,
        ) = 98

        // Children never fill — we only test up to entry in these tests
        override suspend fun awaitChildFill(orderId: Int): OrderFill =
            kotlinx.coroutines.delay(Long.MAX_VALUE).let { OrderFill(OrderStatus.CANCELLED) }

        override suspend fun submitMarketSell(
            symbol: Symbol,
            shares: Int,
        ) = 99
    }
}
