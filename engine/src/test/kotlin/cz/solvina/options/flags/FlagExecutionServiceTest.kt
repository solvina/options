package cz.solvina.options.flags

import cz.solvina.options.domain.features.flag.BracketOrderIds
import cz.solvina.options.domain.features.flag.BracketOrderPort
import cz.solvina.options.domain.features.flag.EntryFill
import cz.solvina.options.domain.features.flag.FlagExecutionService
import cz.solvina.options.domain.features.flag.FlagPage
import cz.solvina.options.domain.features.flag.FlagPort
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun buildService(
        flagPort: FlagPort = CapturingFlagPort(),
        bracketPort: BracketOrderPort = ImmediateFillBracketPort(),
        scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.GlobalScope,
    ) = FlagExecutionService(
        bracketOrderPort = bracketPort,
        flagPort = flagPort,
        clock = fixedClock,
        scope = scope,
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

        override suspend fun awaitParentFill(orderId: Int) = EntryFill(status = OrderStatus.FILLED, avgPrice = entryFillPrice)

        override suspend fun rewatchParentFill(orderId: Int) = awaitParentFill(orderId)

        override suspend fun rewatchChildFill(orderId: Int): OrderStatus = awaitChildFill(orderId)

        override fun hasActiveWatch(orderId: Int) = false

        override suspend fun submitTrailingStopSell(
            symbol: Symbol,
            shares: Int,
            initialStop: BigDecimal,
            trailAmount: BigDecimal,
        ) = 98

        // Children never fill — we only test up to entry in these tests
        override suspend fun awaitChildFill(orderId: Int): OrderStatus =
            kotlinx.coroutines.delay(Long.MAX_VALUE).let { OrderStatus.CANCELLED }

        override suspend fun submitMarketSell(
            symbol: Symbol,
            shares: Int,
        ) = 99
    }
}
