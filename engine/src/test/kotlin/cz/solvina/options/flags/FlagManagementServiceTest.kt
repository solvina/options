package cz.solvina.options.flags

import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.flag.BracketOrderPort
import cz.solvina.options.domain.features.flag.EntryFill
import cz.solvina.options.domain.features.flag.FlagManagementService
import cz.solvina.options.domain.features.flag.FlagPage
import cz.solvina.options.domain.features.flag.FlagPort
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfigPort
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.flag.model.isTerminal
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Position lifecycle rules for bull-flag equity trades:
 *
 *   Watermarks  — highestPriceSeen and lowestPriceSeen update on every price tick.
 *                 They drive MFE/MAE which tell us how much headroom the position had.
 *
 *   MFE         — Max Favorable Excursion = (highestPriceSeen − actualEntry) × shares.
 *                 How much profit was available at the best point during the trade.
 *
 *   MAE         — Max Adverse Excursion = (actualEntry − lowestPriceSeen) × shares.
 *                 How close to the stop-loss price the trade actually got.
 *
 *   EOD         — Near session close, open positions are force-liquidated to avoid
 *                 overnight gap risk. Bracket orders are cancelled and shares are sold
 *                 at market. Status transitions to CLOSED_EOD.
 */
class FlagManagementServiceTest {
    // -------------------------------------------------------------------------
    // Shared fixtures
    // -------------------------------------------------------------------------

    private val symbol = Symbol("AAPL")
    private val fixedClock = Clock.fixed(Instant.parse("2025-06-05T14:00:00Z"), ZoneOffset.UTC)

    private fun openPosition(
        entryPrice: BigDecimal = BigDecimal("150.00"),
        stopLossPrice: BigDecimal = BigDecimal("147.00"),
        shares: Int = 10,
        highestPriceSeen: BigDecimal? = null,
        lowestPriceSeen: BigDecimal? = null,
        marketSession: String? = null,
    ) = FlagPosition(
        id = UUID.randomUUID(),
        symbol = symbol,
        status = FlagStatus.OPEN,
        entryOrderId = 1,
        stopLossOrderId = 2,
        profitTargetOrderId = 3,
        entryPrice = entryPrice,
        stopLossPrice = stopLossPrice,
        profitTargetPrice = BigDecimal("156.00"), // entry + 2 × (entry − stop) = 150 + 6 = 156
        shares = shares,
        riskAmount = BigDecimal("100.00"),
        flagpoleHeight = null,
        flagRetracement = null,
        resistanceAtEntry = null,
        patternStartedAt = null,
        openedAt = Instant.parse("2025-06-05T09:30:00Z"),
        highestPriceSeen = highestPriceSeen,
        lowestPriceSeen = lowestPriceSeen,
        marketSession = marketSession,
    )

    // -------------------------------------------------------------------------
    // Watermark tracking
    // -------------------------------------------------------------------------

    @Test
    fun `high watermark advances when a new price exceeds the current maximum`() =
        runTest {
            val position = openPosition(highestPriceSeen = BigDecimal("152.00"))
            val flagPort = InMemoryFlagPort(listOf(position))
            val service = buildService(flagPort = flagPort, currentPrice = BigDecimal("155.00"))

            service.updateWatermarksForSymbol(symbol, BigDecimal("155.00"))

            val updated = flagPort.findOpen().first()
            assertEquals(BigDecimal("155.00"), updated.highestPriceSeen)
        }

    @Test
    fun `high watermark does not retreat when a lower price arrives`() =
        runTest {
            val position = openPosition(highestPriceSeen = BigDecimal("152.00"))
            val flagPort = InMemoryFlagPort(listOf(position))
            val service = buildService(flagPort = flagPort, currentPrice = BigDecimal("149.00"))

            service.updateWatermarksForSymbol(symbol, BigDecimal("149.00"))

            val updated = flagPort.findOpen().first()
            assertEquals(BigDecimal("152.00"), updated.highestPriceSeen, "High watermark must be monotonically increasing")
        }

    @Test
    fun `low watermark retreats when a new price falls below the current minimum`() =
        runTest {
            val position = openPosition(lowestPriceSeen = BigDecimal("149.00"))
            val flagPort = InMemoryFlagPort(listOf(position))
            val service = buildService(flagPort = flagPort, currentPrice = BigDecimal("147.50"))

            service.updateWatermarksForSymbol(symbol, BigDecimal("147.50"))

            val updated = flagPort.findOpen().first()
            assertEquals(BigDecimal("147.50"), updated.lowestPriceSeen)
        }

    // -------------------------------------------------------------------------
    // MFE / MAE at close
    // -------------------------------------------------------------------------

    @Test
    fun `MFE reflects the best profit available during the trade`() =
        runTest {
            // entry=$150, highWatermark=$155, shares=10
            // MFE = ($155 − $150) × 10 = $50
            val position =
                openPosition(
                    entryPrice = BigDecimal("150.00"),
                    shares = 10,
                    highestPriceSeen = BigDecimal("155.00"),
                    lowestPriceSeen = BigDecimal("148.00"),
                )
            val flagPort = InMemoryFlagPort(listOf(position))
            val service = buildService(flagPort = flagPort, currentPrice = BigDecimal("154.00"))

            service.manualClose(position.id!!)

            val closed = flagPort.saved.last()
            assertEquals(
                BigDecimal("50.00"),
                closed.maxFavorableExcursion,
                "MFE = (highWatermark − entry) × shares",
            )
        }

    @Test
    fun `MAE reflects the maximum drawdown risk the trade absorbed`() =
        runTest {
            // entry=$150, lowWatermark=$148, shares=10
            // MAE = ($150 − $148) × 10 = $20
            val position =
                openPosition(
                    entryPrice = BigDecimal("150.00"),
                    shares = 10,
                    highestPriceSeen = BigDecimal("153.00"),
                    lowestPriceSeen = BigDecimal("148.00"),
                )
            val flagPort = InMemoryFlagPort(listOf(position))
            val service = buildService(flagPort = flagPort, currentPrice = BigDecimal("151.00"))

            service.manualClose(position.id!!)

            val closed = flagPort.saved.last()
            assertEquals(
                BigDecimal("20.00"),
                closed.maxAdverseExcursion,
                "MAE = (entry − lowWatermark) × shares",
            )
        }

    // -------------------------------------------------------------------------
    // EOD liquidation
    // -------------------------------------------------------------------------

    @Test
    fun `EOD liquidation cancels bracket children and sells shares at market`() =
        runTest {
            val position = openPosition()
            val flagPort = InMemoryFlagPort(listOf(position))
            val bracketPort = RecordingBracketOrderPort()
            val service = buildService(flagPort = flagPort, bracketPort = bracketPort)

            service.checkEodLiquidation()

            assertTrue(
                position.stopLossOrderId in bracketPort.cancelledOrders,
                "Stop-loss child order must be cancelled before market sell",
            )
            assertTrue(
                position.profitTargetOrderId in bracketPort.cancelledOrders,
                "Profit-target child order must be cancelled before market sell",
            )
            assertTrue(
                bracketPort.marketSells.any { it.first == symbol && it.second == position.shares },
                "Shares must be sold at market during EOD liquidation",
            )
        }

    @Test
    fun `EOD liquidation marks the position as CLOSED_EOD`() =
        runTest {
            val position = openPosition()
            val flagPort = InMemoryFlagPort(listOf(position))
            val service = buildService(flagPort = flagPort)

            service.checkEodLiquidation()

            val closed = flagPort.saved.last()
            assertEquals(FlagStatus.CLOSED_EOD, closed.status)
        }

    @Test
    fun `EOD liquidation only closes positions for the requested session`() =
        runTest {
            val usPosition = openPosition(marketSession = "US")
            val euPosition = openPosition(marketSession = "EU")
            val flagPort = InMemoryFlagPort(listOf(usPosition, euPosition))
            val service = buildService(flagPort = flagPort)

            service.checkEodLiquidation(session = "EU")

            // Only the EU position should be closed
            val closedIds = flagPort.saved.filter { it.status == FlagStatus.CLOSED_EOD }.map { it.id }
            assertTrue(euPosition.id in closedIds, "EU position must be closed")
            assertTrue(usPosition.id !in closedIds, "US position must remain open")
        }

    // -------------------------------------------------------------------------
    // Broker-verified closes (short-stock-orphan fix, 2026-07)
    // -------------------------------------------------------------------------

    @Test
    fun `manual close aborts before touching any order when broker holdings cannot be verified`() =
        runTest {
            val position = openPosition()
            val flagPort = InMemoryFlagPort(listOf(position))
            val bracketPort = RecordingBracketOrderPort()
            // An empty snapshot is indistinguishable from a feed still warming up — never trust it.
            val service = buildService(flagPort = flagPort, bracketPort = bracketPort, brokerHoldings = emptyList())

            val result = service.manualClose(position.id!!)

            assertTrue(result is FlagManagementService.ManualCloseResult.Failed, "Close must report failure, got $result")
            assertTrue(bracketPort.cancelledOrders.isEmpty(), "Protective orders must survive an aborted close")
            assertTrue(bracketPort.marketSells.isEmpty(), "Nothing may be sold blind")
            assertEquals(FlagStatus.OPEN, flagPort.findById(position.id!!)!!.status, "Position must stay OPEN")
        }

    @Test
    fun `close of a position whose exit already filled is booked as CLOSED_EXTERNAL without selling`() =
        runTest {
            val position = openPosition()
            val flagPort = InMemoryFlagPort(listOf(position))
            val bracketPort = RecordingBracketOrderPort()
            // Snapshot is trustworthy (non-empty) but holds none of the symbol: the trailing stop
            // already fired while the engine was not watching.
            val service = buildService(flagPort = flagPort, bracketPort = bracketPort, brokerHoldings = listOf(stkHolding("MSFT", 50)))

            val result = service.manualClose(position.id!!)

            assertTrue(result is FlagManagementService.ManualCloseResult.Closed, "Close must succeed administratively, got $result")
            assertTrue(bracketPort.marketSells.isEmpty(), "Selling here is exactly the double-sell that created orphan shorts")
            assertEquals(FlagStatus.CLOSED_EXTERNAL, flagPort.findById(position.id!!)!!.status)
        }

    @Test
    fun `close sells only the quantity the broker actually holds`() =
        runTest {
            val position = openPosition(shares = 10)
            val flagPort = InMemoryFlagPort(listOf(position))
            val bracketPort = RecordingBracketOrderPort()
            val service = buildService(flagPort = flagPort, bracketPort = bracketPort, brokerHoldings = listOf(stkHolding("AAPL", 4)))

            service.manualClose(position.id!!)

            assertEquals(listOf(symbol to 4), bracketPort.marketSells, "Sell must be capped at the held quantity")
            assertEquals(FlagStatus.CLOSED_MANUAL, flagPort.findById(position.id!!)!!.status)
        }

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    /** Broker STK holding for [sym] — what the positions feed reports the account actually holds. */
    private fun stkHolding(
        sym: String,
        qty: Int,
    ) = AccountPosition(
        account = "DU1",
        symbol = sym,
        secType = "STK",
        currency = "USD",
        expiry = null,
        strike = null,
        optionRight = null,
        quantity = BigDecimal(qty),
        avgCost = BigDecimal("150"),
    )

    private fun buildService(
        flagPort: FlagPort = InMemoryFlagPort(),
        bracketPort: BracketOrderPort = RecordingBracketOrderPort(),
        currentPrice: BigDecimal = BigDecimal("151.00"),
        // Default: the broker holds plenty of the test symbol, so closes behave as before.
        brokerHoldings: List<AccountPosition> = listOf(stkHolding("AAPL", 100)),
        positionsPort: PositionsPort =
            object : PositionsPort {
                override suspend fun getPositions() = brokerHoldings
            },
    ) = FlagManagementService(
        flagPort = flagPort,
        bracketOrderPort = bracketPort,
        flagTradingConfigPort =
            object : FlagTradingConfigPort {
                private var config = FlagTradingConfig()

                override suspend fun get() = config

                override suspend fun update(config: FlagTradingConfig) = config.also { this.config = it }
            },
        marketDataPort =
            object : MarketDataPort {
                override suspend fun getUnderlyingPrice(symbol: Symbol) = Money(currentPrice)

                override suspend fun getOptionMid(contract: OptionContract) = Money(BigDecimal.ZERO)
            },
        positionsPort = positionsPort,
        clock = fixedClock,
    )

    /**
     * In-memory [FlagPort] backed by a mutable map so that [update] calls are visible to [findOpen].
     * [saved] records every write in insertion order for assertion convenience.
     */
    private class InMemoryFlagPort(
        initialPositions: List<FlagPosition> = emptyList(),
    ) : FlagPort {
        private val store = initialPositions.associateBy { it.id!! }.toMutableMap()
        val saved = mutableListOf<FlagPosition>()

        override suspend fun save(position: FlagPosition) =
            position.also {
                store[position.id!!] = position
                saved.add(position)
            }

        override suspend fun update(position: FlagPosition) =
            position.also {
                store[position.id!!] = position
                saved.add(position)
            }

        override suspend fun findOpen() = store.values.filter { !it.status.isTerminal }

        override suspend fun findById(id: UUID) = store[id]

        override suspend fun findAll() = store.values.toList()

        override suspend fun findByStatus(status: FlagStatus) = store.values.filter { it.status == status }

        override suspend fun countByStatus(status: FlagStatus) = store.values.count { it.status == status }.toLong()

        override suspend fun findPage(
            status: FlagStatus?,
            page: Int,
            size: Int,
            sort: String,
            sortDir: String,
        ) = FlagPage(emptyList(), 0, 0, page, size)
    }

    /** Records all cancel and market-sell calls so tests can verify what was submitted. */
    private class RecordingBracketOrderPort : BracketOrderPort {
        val cancelledOrders = mutableListOf<Int>()
        val marketSells = mutableListOf<Pair<Symbol, Int>>()

        override suspend fun submitBracketOrder(
            symbol: Symbol,
            shares: Int,
            entryPrice: BigDecimal,
            stopLossPrice: BigDecimal,
            trailAmount: BigDecimal,
        ) = throw UnsupportedOperationException("not needed in this test")

        override suspend fun cancelOrder(orderId: Int) {
            cancelledOrders.add(orderId)
        }

        override suspend fun awaitParentFill(orderId: Int) = EntryFill(status = OrderStatus.FILLED, avgPrice = BigDecimal("150.00"))

        override suspend fun awaitChildFill(orderId: Int): OrderStatus = OrderStatus.FILLED

        override suspend fun rewatchParentFill(orderId: Int) = awaitParentFill(orderId)

        override suspend fun rewatchChildFill(orderId: Int): OrderStatus = awaitChildFill(orderId)

        override fun hasActiveWatch(orderId: Int) = false

        override suspend fun submitTrailingStopSell(
            symbol: Symbol,
            shares: Int,
            initialStop: BigDecimal,
            trailAmount: BigDecimal,
        ) = 998

        override suspend fun submitMarketSell(
            symbol: Symbol,
            shares: Int,
        ): Int {
            marketSells.add(symbol to shares)
            return 999
        }
    }
}
