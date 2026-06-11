package cz.solvina.options.domain.features.spread

import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.LegOrder
import cz.solvina.options.domain.features.order.OrderPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PartialFillDetectionServiceTest {
    private val orderPort: OrderPort = mockk()
    private val registry: IbkrOrderRegistry = mockk()
    private val service = PartialFillDetectionService(orderPort, registry)

    private val testSpread = BullPutSpread(
        id = UUID.randomUUID(),
        symbol = Symbol("AMD"),
        soldLeg = SpreadLeg(
            contract = OptionContract(
                symbol = Symbol("AMD"),
                strike = BigDecimal("45.00"),
                expiry = LocalDate.of(2024, 6, 21),
                type = OptionType.PUT,
            ),
            action = LegAction.SELL,
            premium = Money(BigDecimal("1.00")),
            orderId = 0,
        ),
        boughtLeg = SpreadLeg(
            contract = OptionContract(
                symbol = Symbol("AMD"),
                strike = BigDecimal("40.00"),
                expiry = LocalDate.of(2024, 6, 21),
                type = OptionType.PUT,
            ),
            action = LegAction.BUY,
            premium = Money(BigDecimal("0.50")),
            orderId = 0,
        ),
        creditPerShare = BigDecimal("1.50"),
        maxRiskPerShare = BigDecimal("3.50"),
        status = SpreadStatus.OPEN,
        ivRankAtEntry = 65.0,
        underlyingPriceAtEntry = BigDecimal("145.00"),
        openedAt = Instant.now(),
    )

    @Test
    fun `issue 1 - both legs fill successfully`() = runTest {
        val soldOrderId = 1001
        val boughtOrderId = 1002

        val soldDeferred = CompletableDeferred<OrderStatus>()
        val boughtDeferred = CompletableDeferred<OrderStatus>()

        coEvery { orderPort.placeMarketOrder(any(), LegAction.BUY, any()) }
            .returns(LegOrder(soldOrderId, OrderStatus.PENDING))

        coEvery { orderPort.placeMarketOrder(any(), LegAction.SELL, any()) }
            .returns(LegOrder(boughtOrderId, OrderStatus.PENDING))

        coEvery { registry.pendingOrderStatus }
            .returns(ConcurrentHashMap(mapOf(soldOrderId to soldDeferred, boughtOrderId to boughtDeferred)))

        // Both fill successfully
        soldDeferred.complete(OrderStatus.FILLED)
        boughtDeferred.complete(OrderStatus.FILLED)

        val result = service.closeWithPartialFillDetection(
            testSpread,
            testSpread.soldLeg.contract,
            testSpread.boughtLeg.contract,
            1,
            maxWaitMs = 2000,
        )

        assertTrue(result.fullyFilled)
        assertFalse(result.partialFillDetected)
        assertEquals("Both legs filled", result.reason)
    }

    @Test
    fun `issue 1 - sold leg fills but bought leg fails`() = runTest {
        val soldOrderId = 2001
        val boughtOrderId = 2002

        val soldDeferred = CompletableDeferred<OrderStatus>()
        val boughtDeferred = CompletableDeferred<OrderStatus>()

        coEvery { orderPort.placeMarketOrder(any(), LegAction.BUY, any()) }
            .returns(LegOrder(soldOrderId, OrderStatus.PENDING))

        coEvery { orderPort.placeMarketOrder(any(), LegAction.SELL, any()) }
            .returns(LegOrder(boughtOrderId, OrderStatus.PENDING))

        coEvery { registry.pendingOrderStatus }
            .returns(ConcurrentHashMap(mapOf(soldOrderId to soldDeferred, boughtOrderId to boughtDeferred)))

        // SOLD fills, BOUGHT times out
        soldDeferred.complete(OrderStatus.FILLED)
        boughtDeferred.complete(OrderStatus.CANCELLED)

        // Compensating order succeeds
        coEvery { orderPort.placeMarketOrder(any(), LegAction.SELL, any()) }
            .returns(LegOrder(2003, OrderStatus.PENDING))

        val result = service.closeWithPartialFillDetection(
            testSpread,
            testSpread.soldLeg.contract,
            testSpread.boughtLeg.contract,
            1,
            maxWaitMs = 2000,
        )

        assertFalse(result.fullyFilled)
        assertTrue(result.partialFillDetected)
        assertTrue(result.compensatingOrderPlaced)
        assertTrue(result.reason.contains("SOLD filled, BOUGHT failed"))
    }

    @Test
    fun `issue 1 - bought leg fills but sold leg fails`() = runTest {
        val soldOrderId = 3001
        val boughtOrderId = 3002

        val soldDeferred = CompletableDeferred<OrderStatus>()
        val boughtDeferred = CompletableDeferred<OrderStatus>()

        coEvery { orderPort.placeMarketOrder(any(), LegAction.BUY, any()) }
            .returns(LegOrder(soldOrderId, OrderStatus.PENDING))

        coEvery { orderPort.placeMarketOrder(any(), LegAction.SELL, any()) }
            .returns(LegOrder(boughtOrderId, OrderStatus.PENDING))

        coEvery { registry.pendingOrderStatus }
            .returns(ConcurrentHashMap(mapOf(soldOrderId to soldDeferred, boughtOrderId to boughtDeferred)))

        // BOUGHT fills, SOLD times out
        soldDeferred.complete(OrderStatus.CANCELLED)
        boughtDeferred.complete(OrderStatus.FILLED)

        // Compensating order succeeds
        coEvery { orderPort.placeMarketOrder(any(), LegAction.BUY, any()) }
            .returns(LegOrder(3003, OrderStatus.PENDING))

        val result = service.closeWithPartialFillDetection(
            testSpread,
            testSpread.soldLeg.contract,
            testSpread.boughtLeg.contract,
            1,
            maxWaitMs = 2000,
        )

        assertFalse(result.fullyFilled)
        assertTrue(result.partialFillDetected)
        assertTrue(result.compensatingOrderPlaced)
        assertTrue(result.reason.contains("BOUGHT filled, SOLD failed"))
    }

    @Test
    fun `issue 1 - both legs timeout without filling`() = runTest {
        val soldOrderId = 4001
        val boughtOrderId = 4002

        val soldDeferred = CompletableDeferred<OrderStatus>()
        val boughtDeferred = CompletableDeferred<OrderStatus>()

        coEvery { orderPort.placeMarketOrder(any(), LegAction.BUY, any()) }
            .returns(LegOrder(soldOrderId, OrderStatus.PENDING))

        coEvery { orderPort.placeMarketOrder(any(), LegAction.SELL, any()) }
            .returns(LegOrder(boughtOrderId, OrderStatus.PENDING))

        coEvery { registry.pendingOrderStatus }
            .returns(ConcurrentHashMap(mapOf(soldOrderId to soldDeferred, boughtOrderId to boughtDeferred)))

        // Both timeout (neither completes)
        val result = service.closeWithPartialFillDetection(
            testSpread,
            testSpread.soldLeg.contract,
            testSpread.boughtLeg.contract,
            1,
            maxWaitMs = 100, // Short timeout for test
        )

        assertFalse(result.fullyFilled)
        assertFalse(result.partialFillDetected)
        assertEquals("Both legs timed out without filling", result.reason)
    }

    @Test
    fun `issue 1 - order placement exception`() = runTest {
        coEvery { orderPort.placeMarketOrder(any(), any(), any()) }
            .throws(Exception("Connection lost"))

        val result = service.closeWithPartialFillDetection(
            testSpread,
            testSpread.soldLeg.contract,
            testSpread.boughtLeg.contract,
            1,
            maxWaitMs = 100,
        )

        assertFalse(result.fullyFilled)
        assertFalse(result.partialFillDetected)
        assertTrue(result.reason.contains("Exception"))
    }
}
