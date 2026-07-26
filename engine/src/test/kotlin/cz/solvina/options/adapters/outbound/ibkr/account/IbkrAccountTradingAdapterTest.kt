package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.Decimal
import com.ib.client.EClientSocket
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderIdCounter
import cz.solvina.options.domain.features.account.AccountOrderNotCancellableException
import cz.solvina.options.domain.features.account.AccountOrderNotFoundException
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IbkrAccountTradingAdapterTest {
    private val registry = IbkrOrdersRegistry()
    private val client = mockk<EClientSocket>()
    private val adapter = IbkrAccountTradingAdapter(registry, client, IbkrOrderIdCounter.testCounter(), IbkrConnectionConfig(clientId = 1))

    @Test
    fun `cancelOrder rejects unknown order ids before hitting IBKR`() =
        runTest {
            assertFailsWith<AccountOrderNotFoundException> {
                adapter.cancelOrder(0)
            }

            verify(exactly = 0) { client.cancelOrder(any(), any<OrderCancel>()) }
        }

    @Test
    fun `cancelOrder marks self-cancelled and sends positive ids to IBKR`() =
        runTest {
            registry.status(orderId = 42, clientId = 1)
            every { client.cancelOrder(42, any<OrderCancel>()) } just runs

            adapter.cancelOrder(42)

            assertTrue(registry.wasSelfCancelled(42))
            verify { client.cancelOrder(42, any<OrderCancel>()) }
        }

    @Test
    fun `cancelOrder rejects orders owned by another API client`() =
        runTest {
            registry.status(orderId = 43, clientId = 0)

            assertFailsWith<AccountOrderNotCancellableException> {
                adapter.cancelOrder(43)
            }

            verify(exactly = 0) { client.cancelOrder(any(), any<OrderCancel>()) }
        }

    @Test
    fun `open order rows expose cancelability for this client`() =
        runTest {
            registry.status(orderId = 44, clientId = 1)
            registry.status(orderId = 45, clientId = 0)

            val rows = adapter.getOpenOrders().associateBy { it.orderId }

            assertTrue(rows.getValue(44).cancellable)
            assertFalse(rows.getValue(45).cancellable)
            assertEquals(0, rows.getValue(45).clientId)
        }

    private fun IbkrOrdersRegistry.status(
        orderId: Int,
        clientId: Int,
    ) {
        onOrderStatus(
            orderId = orderId,
            status = "Submitted",
            filled = Decimal.ZERO,
            remaining = Decimal.ONE,
            avgFillPrice = 0.0,
            permId = orderId + 1_000,
            parentId = 0,
            lastFillPrice = 0.0,
            clientId = clientId,
            whyHeld = null,
            mktCapPrice = 0.0,
        )
    }
}
