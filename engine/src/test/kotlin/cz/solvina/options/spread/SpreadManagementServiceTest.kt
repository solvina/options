package cz.solvina.options.spread

import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.LegOrder
import cz.solvina.options.domain.features.order.OrderPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.spread.SpreadManagementService
import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

class SpreadManagementServiceTest {
    private val symbol = Symbol("SPY")
    private val expiry = LocalDate.of(2025, 3, 21)
    private val soldContract = OptionContract(symbol, expiry, BigDecimal("480"), OptionType.PUT)
    private val boughtContract = OptionContract(symbol, expiry, BigDecimal("475"), OptionType.PUT)

    // Fixed 80 DTE before expiry — well above timeProfitDte(14), prevents accidental time-exit
    private val clockAtEntry =
        Clock.fixed(
            LocalDate.of(2025, 1, 1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
            java.time.ZoneOffset.UTC,
        )

    private val config = ScannerConfig(watchlist = listOf("SPY"))

    private val filledOrder = LegOrder(orderId = 99, status = OrderStatus.FILLED)

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

    /**
     * The bug: SpreadManagementService.closeSpread() priced the sell-back of the bought leg as
     *   spread.boughtLeg.contract.strike.multiply(BigDecimal.ZERO)  ← always $0
     *
     * The correct price is marketDataPort.getOptionMid(boughtLeg.contract).
     */
    @Test
    fun `sell-back of bought leg uses market mid price, not zero`() =
        runTest {
            // Spread opened at $1.00 credit.
            // TP threshold = 1.00 × (1 - 0.50) = $0.50
            // Current prices: soldMid=$0.30, boughtMid=$0.05 → spread value=$0.25 → below TP, exit triggered.
            val boughtMid = BigDecimal("0.05")

            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findOpen() } returns listOf(buildOpenSpread())
            coEvery { spreadPort.update(any()) } answers { firstArg() }

            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("0.30"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(boughtMid)

            coEvery { orderPort.placeAndAwaitFill(any(), any(), any(), any()) } returns filledOrder

            SpreadManagementService(
                spreadPort = spreadPort,
                marketDataPort = marketDataPort,
                orderPort = orderPort,
                config = config,
                clock = clockAtEntry,
            ).checkExits()

            val sellBackPrice = slot<Money>()
            coVerify {
                orderPort.placeAndAwaitFill(
                    contract = boughtContract,
                    action = LegAction.SELL,
                    limitPrice = capture(sellBackPrice),
                    qty = any(),
                )
            }

            assertEquals(
                boughtMid,
                sellBackPrice.captured.amount,
                "Sell-back limitPrice should be market mid \$$boughtMid, got ${sellBackPrice.captured.amount}",
            )
        }

    @Test
    fun `stop loss closes spread when value exceeds threshold`() =
        runTest {
            // credit=$1.00, maxRisk=$4.00, stopLossPct=0.50
            // SL threshold = 1.00 + 4.00 × 0.50 = $3.00
            // current spread value = $3.50 → above SL
            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findOpen() } returns listOf(buildOpenSpread())
            coEvery { spreadPort.update(any()) } answers { firstArg() }

            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("3.70"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.20"))

            coEvery { orderPort.placeAndAwaitFill(any(), any(), any(), any()) } returns filledOrder

            val updatedSpread = slot<BullPutSpread>()
            coEvery { spreadPort.update(capture(updatedSpread)) } answers { firstArg() }

            SpreadManagementService(
                spreadPort = spreadPort,
                marketDataPort = marketDataPort,
                orderPort = orderPort,
                config = config,
                clock = clockAtEntry,
            ).checkExits()

            assertEquals(SpreadStatus.CLOSED_STOP, updatedSpread.captured.status)
        }

    @Test
    fun `time exit closes spread when DTE reaches threshold`() =
        runTest {
            // expiry is 2025-03-21; timeProfitDte=14; use a date 10 days before expiry
            val nearExpiry = LocalDate.of(2025, 3, 21).minusDays(10)
            val clock = Clock.fixed(nearExpiry.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), java.time.ZoneOffset.UTC)

            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findOpen() } returns listOf(buildOpenSpread())
            coEvery { spreadPort.update(any()) } answers { firstArg() }

            // Prices that don't trigger TP or SL: spread value = $0.60 (above TP=$0.50, below SL=$3.00)
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("0.70"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.10"))

            coEvery { orderPort.placeAndAwaitFill(any(), any(), any(), any()) } returns filledOrder

            val updatedSpread = slot<BullPutSpread>()
            coEvery { spreadPort.update(capture(updatedSpread)) } answers { firstArg() }

            SpreadManagementService(
                spreadPort = spreadPort,
                marketDataPort = marketDataPort,
                orderPort = orderPort,
                config = config,
                clock = clock,
            ).checkExits()

            assertEquals(SpreadStatus.CLOSED_TIME, updatedSpread.captured.status)
        }

    @Test
    fun `no exit when spread value is between TP and SL thresholds`() =
        runTest {
            val spreadPort = mockk<SpreadPort>()
            val marketDataPort = mockk<MarketDataPort>()
            val orderPort = mockk<OrderPort>()

            coEvery { spreadPort.findOpen() } returns listOf(buildOpenSpread())

            // TP=$0.50, SL=$3.00; spread value=$0.60 — no exit
            coEvery { marketDataPort.getOptionMid(soldContract) } returns Money(BigDecimal("0.70"))
            coEvery { marketDataPort.getOptionMid(boughtContract) } returns Money(BigDecimal("0.10"))

            SpreadManagementService(
                spreadPort = spreadPort,
                marketDataPort = marketDataPort,
                orderPort = orderPort,
                config = config,
                clock = clockAtEntry,
            ).checkExits()

            coVerify(exactly = 0) { orderPort.placeAndAwaitFill(any(), any(), any(), any()) }
            coVerify(exactly = 0) { spreadPort.update(any()) }
        }
}
