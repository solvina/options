package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.Decimal
import cz.solvina.options.domain.features.order.OrderStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class IbkrOrdersRegistryTest {
    @Test
    fun `records orderStatus even when openOrder was not seen first`() {
        val registry = IbkrOrdersRegistry()

        registry.status(orderId = 10, status = "Filled", avgFillPrice = 1.23, filled = 1)

        assertTrue(registry.isFilled(10))
        assertEquals(
            OrderStatus.FILLED,
            registry.current(10)?.orderStatus,
        )
        assertEquals(BigDecimal("1.2300"), registry.consumeFillPrice(10))
        assertEquals("Filled", registry.getAllOrders().single().status)
    }

    @Test
    fun `filters terminal statuses case-insensitively from open orders`() {
        val registry = IbkrOrdersRegistry()

        registry.status(orderId = 10, status = "Filled", avgFillPrice = 1.23, filled = 1)
        registry.status(orderId = 11, status = "ApiCancelled")
        registry.status(orderId = 12, status = "Submitted", remaining = 1)

        assertEquals(listOf(12), registry.getOpenOrders().map { it.orderId })
    }

    @Test
    fun `self cancelled order is terminal but does not expose a reject reason`() {
        val registry = IbkrOrdersRegistry()

        registry.markSelfCancelled(20)
        registry.onError(20, 202, "Order cancelled")

        assertTrue(registry.wasSelfCancelled(20))
        assertTrue(registry.isCancelled(20))
        assertNull(registry.consumeRejectReason(20))
    }

    @Test
    fun `broker reject reason is consumed one-shot`() {
        val registry = IbkrOrdersRegistry()

        registry.onError(30, 201, "Rejected by risk")

        assertTrue(registry.isCancelled(30))
        assertEquals("code=201: Rejected by risk", registry.consumeRejectReason(30))
        assertNull(registry.consumeRejectReason(30))
    }

    @Test
    fun `awaitTerminal returns later broker callback from update stream`() =
        runTest {
            val registry = IbkrOrdersRegistry()

            val awaited = async { registry.awaitTerminal(40, 1_000.milliseconds) }
            registry.status(orderId = 40, status = "Cancelled")

            assertEquals(OrderStatus.CANCELLED, awaited.await())
        }

    @Test
    fun `awaitTerminal completes from registry state even without a flow subscriber`() =
        runTest {
            val registry = IbkrOrdersRegistry()

            val awaited = async { registry.awaitTerminal(41, 1_000.milliseconds) }
            delay(1)
            registry.status(orderId = 41, status = "Rejected")

            assertEquals(OrderStatus.REJECTED, awaited.await())
        }

    @Test
    fun `preserves distinct non-filled terminal statuses`() =
        runTest {
            val registry = IbkrOrdersRegistry()

            registry.status(orderId = 61, status = "ApiCancelled")
            registry.status(orderId = 62, status = "Inactive")
            registry.status(orderId = 63, status = "Rejected")

            assertEquals(OrderStatus.API_CANCELLED, registry.awaitTerminal(61, 1.milliseconds))
            assertEquals(OrderStatus.INACTIVE, registry.awaitTerminal(62, 1.milliseconds))
            assertEquals(OrderStatus.REJECTED, registry.awaitTerminal(63, 1.milliseconds))
        }

    @Test
    fun `awaitTerminal returns pending on timeout without inventing a cancellation`() =
        runTest {
            val registry = IbkrOrdersRegistry()

            assertEquals(OrderStatus.PENDING, registry.awaitTerminal(50, 1.milliseconds))
            assertFalse(registry.isCancelled(50))
        }

    private fun IbkrOrdersRegistry.status(
        orderId: Int,
        status: String,
        avgFillPrice: Double = 0.0,
        filled: Long = 0,
        remaining: Long = 0,
    ) {
        onOrderStatus(
            orderId = orderId,
            status = status,
            filled = Decimal.get(filled),
            remaining = Decimal.get(remaining),
            avgFillPrice = avgFillPrice,
            permId = 0,
            parentId = 0,
            lastFillPrice = avgFillPrice,
            clientId = 0,
            whyHeld = null,
            mktCapPrice = 0.0,
        )
    }
}
