package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.EClientSocket
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.account.OpenOrder
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderCancellationServiceTest {
    private val client: EClientSocket = mockk(relaxed = true)
    private val openOrdersAdapter: IbkrOpenOrdersAdapter = mockk()
    private val registry: IbkrOrderRegistry = mockk(relaxed = true)
    private val service = OrderCancellationService(client, openOrdersAdapter, registry)

    @Test
    fun `atomic cancel verifies order state before issuing cancel`() =
        runTest {
            val orderId = 123
            val openOrder =
                OpenOrder(
                    orderId = orderId,
                    symbol = "AMD",
                    action = "SELL",
                    orderType = "LMT",
                    limitPrice = 45.5,
                    status = "Submitted",
                )

            // First call returns order exists, then after cancel returns empty
            coEvery { openOrdersAdapter.getOpenOrders() }
                .coAnswers { listOf(openOrder) }
                .andThen { emptyList() }

            val results = service.cancelOrdersAtomic(listOf(orderId), "test_cancel")

            assertEquals(1, results.size)
            assertTrue(results[0].success)
            assertEquals("verified_removed", results[0].reason)
            verify { client.cancelOrder(orderId, any<OrderCancel>()) }
            // Our own cancel must be marked self-cancelled so the broker's code-202 ack is logged
            // at DEBUG (not ERROR "check account permissions") and stashes no reject reason.
            verify { registry.markSelfCancelled(orderId) }
        }

    @Test
    fun `stale order already filled before cancel is skipped`() =
        runTest {
            val orderId = 456
            val filledOrder =
                OpenOrder(
                    orderId = orderId,
                    symbol = "TSLA",
                    action = "SELL",
                    orderType = "LMT",
                    limitPrice = 250.0,
                    status = "Filled",
                )

            coEvery { openOrdersAdapter.getOpenOrders() }
                .returns(listOf(filledOrder))

            val results = service.cancelOrdersAtomic(listOf(orderId), "test_filled")

            assertEquals(1, results.size)
            assertTrue(results[0].success)
            assertEquals("already_filled_or_not_found", results[0].reason)
            verify(exactly = 0) { client.cancelOrder(any(), any<OrderCancel>()) }
        }

    @Test
    fun `cancel with verification loop ensures confirmation`() =
        runTest {
            val orderId = 789
            val openOrder =
                OpenOrder(
                    orderId = orderId,
                    symbol = "MSFT",
                    action = "BUY",
                    orderType = "LMT",
                    limitPrice = 350.0,
                    status = "Submitted",
                )

            // Simulates order still present for 2 attempts, then removed
            coEvery { openOrdersAdapter.getOpenOrders() }
                .coAnswers { listOf(openOrder) } // Initial check before cancel
                .andThenAnswer { listOf(openOrder) } // Retry 1: still there
                .andThenAnswer { listOf(openOrder) } // Retry 2: still there
                .andThenAnswer { emptyList() } // Retry 3: removed

            val results = service.cancelOrdersAtomic(listOf(orderId), "test_verification")

            assertEquals(1, results.size)
            assertTrue(results[0].success)
            assertEquals(3, results[0].attemptCount)
            verify { client.cancelOrder(orderId, any<OrderCancel>()) }
        }

    @Test
    fun `cancel timeout after 5 attempts returns failure`() =
        runTest {
            val orderId = 999
            val openOrder =
                OpenOrder(
                    orderId = orderId,
                    symbol = "GOOG",
                    action = "SELL",
                    orderType = "LMT",
                    limitPrice = 140.0,
                    status = "Submitted",
                )

            // Order never gets removed (simulates verification failure)
            coEvery { openOrdersAdapter.getOpenOrders() }
                .returns(listOf(openOrder))

            val results = service.cancelOrdersAtomic(listOf(orderId), "test_timeout")

            assertEquals(1, results.size)
            assertFalse(results[0].success)
            assertEquals("verification_timeout_after_5_attempts", results[0].reason)
            assertEquals(5, results[0].attemptCount)
        }

    @Test
    fun `empty order list cancels nothing`() =
        runTest {
            val results = service.cancelOrdersAtomic(emptyList(), "test_empty")

            assertEquals(0, results.size)
            verify(exactly = 0) { client.cancelOrder(any(), any<OrderCancel>()) }
        }

    @Test
    fun `multiple orders cancelled atomically`() =
        runTest {
            val orderIds = listOf(111, 222, 333)
            val orders =
                orderIds.map {
                    OpenOrder(
                        orderId = it,
                        symbol = "AMD",
                        action = "SELL",
                        orderType = "LMT",
                        limitPrice = 45.5 + it,
                        status = "Submitted",
                    )
                }

            // All orders exist, then none after cancel
            coEvery { openOrdersAdapter.getOpenOrders() }
                .coAnswers { orders }
                .andThen { emptyList() }

            val results = service.cancelOrdersAtomic(orderIds, "test_batch")

            assertEquals(3, results.size)
            assertTrue(results.all { it.success })
            verify(exactly = 3) { client.cancelOrder(any(), any<OrderCancel>()) }
        }

    @Test
    fun `open orders adapter failure still issues cancel rather than returning false success`() =
        runTest {
            val orderId = 999
            coEvery { openOrdersAdapter.getOpenOrders() } throws RuntimeException("IBKR adapter unavailable")

            val results = service.cancelOrdersAtomic(listOf(orderId), "adapter_failure_test")

            assertEquals(1, results.size)
            // Cancel must be issued even when we can't pre-verify the order state
            verify(exactly = 1) { client.cancelOrder(any(), any<OrderCancel>()) }
            // Verification retries all fail (adapter still throws) — result is failure, not false success
            assertFalse(results[0].success)
        }
}
