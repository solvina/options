package cz.solvina.options.lifecycle

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.inbound.lifecycle.StartupRecoveryService
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.account.OpenOrder
import cz.solvina.options.adapters.outbound.ibkr.order.OrderCancellationService
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.spread.BullPutSpreadPort
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class StartupRecoveryServiceTest {
    private val spreadPort = mockk<BullPutSpreadPort>(relaxed = true)
    private val orderRegistry = mockk<IbkrOrderRegistry>(relaxed = true)
    private val openOrdersAdapter = mockk<IbkrOpenOrdersAdapter>()
    private val client = mockk<EClientSocket>(relaxed = true)
    private val orderCancellationService = mockk<OrderCancellationService>(relaxed = true)
    private val positionsPort = mockk<PositionsPort>()

    private val service =
        StartupRecoveryService(spreadPort, orderRegistry, openOrdersAdapter, client, orderCancellationService, positionsPort)

    private val expiry = LocalDate.of(2026, 7, 17)

    private fun opt(strike: String) = OptionContract(Symbol("AMD"), expiry, BigDecimal(strike), OptionType.PUT)

    private val pending =
        BullPutSpread(
            id = UUID.randomUUID(),
            symbol = Symbol("AMD"),
            soldLeg = SpreadLeg(opt("420"), LegAction.SELL, Money(BigDecimal("1.50")), orderId = 111),
            boughtLeg = SpreadLeg(opt("410"), LegAction.BUY, Money(BigDecimal("0.50")), orderId = 112),
            creditPerShare = BigDecimal("1.00"),
            maxRiskPerShare = BigDecimal("9.00"),
            quantity = 1,
            status = SpreadStatus.PENDING,
            ivRankAtEntry = 50.0,
            underlyingPriceAtEntry = BigDecimal("420"),
            openedAt = Instant.now(),
        )

    /** A held leg matching the spread's expected signed quantity (short = -qty, long = +qty). */
    private fun heldLeg(
        strike: String,
        qty: String,
    ) = AccountPosition(
        account = "DU1",
        symbol = "AMD",
        secType = "OPT",
        currency = "USD",
        expiry = expiry,
        strike = BigDecimal(strike),
        optionRight = "P",
        quantity = BigDecimal(qty),
        avgCost = BigDecimal("100"),
    )

    private fun withPendingSpreadAndNoOpenOrder() {
        coEvery { spreadPort.findByStatus(SpreadStatus.PENDING) } returns listOf(pending)
        coEvery { spreadPort.findByStatus(SpreadStatus.CLOSING) } returns emptyList()
        // Order gone from the broker's open orders — recovery must fall back to the position check.
        coEvery { openOrdersAdapter.getOpenOrders() } returns emptyList<OpenOrder>()
    }

    @Test
    fun `does not close a PENDING spread when the position fetch keeps failing`() =
        runTest {
            withPendingSpreadAndNoOpenOrder()
            coEvery { positionsPort.getPositions() } throws RuntimeException("IBKR positions unavailable")

            service.recover()

            // The caveat: a throwing/unavailable feed must NOT be read as "legs flat" — the spread
            // stays PENDING for re-evaluation rather than being false-closed.
            coVerify(exactly = 0) { spreadPort.update(any()) }
        }

    @Test
    fun `closes as CLOSED_RECOVERY_UNKNOWN when the broker confirms the legs are not held`() =
        runTest {
            withPendingSpreadAndNoOpenOrder()
            // A warm, non-empty snapshot that does not contain this spread's legs is trustworthy
            // evidence they are flat.
            coEvery { positionsPort.getPositions() } returns
                listOf(heldLeg("999", "5"))

            val updated = slot<BullPutSpread>()
            coEvery { spreadPort.update(capture(updated)) } answers { updated.captured }

            service.recover()

            assertEquals(SpreadStatus.CLOSED_RECOVERY_UNKNOWN, updated.captured.status)
            assertEquals("recovery_unknown", updated.captured.closeReason)
        }

    @Test
    fun `adopts as OPEN when both legs are held at the broker`() =
        runTest {
            withPendingSpreadAndNoOpenOrder()
            coEvery { positionsPort.getPositions() } returns
                listOf(heldLeg("420", "-1"), heldLeg("410", "1"))

            val updated = slot<BullPutSpread>()
            coEvery { spreadPort.update(capture(updated)) } answers { updated.captured }

            service.recover()

            assertEquals(SpreadStatus.OPEN, updated.captured.status)
        }
}
