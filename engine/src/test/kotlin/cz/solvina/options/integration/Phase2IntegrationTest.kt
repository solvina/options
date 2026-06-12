package cz.solvina.options.integration

import com.ib.client.EClientSocket
import com.ib.client.OrderCancel
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.account.OpenOrder
import cz.solvina.options.adapters.outbound.ibkr.order.OrderCancellationService
import cz.solvina.options.adapters.outbound.ibkr.order.OrderReplacementService
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for Phase 2 fixes (Issues #3 and #5):
 * - Issue #3: Order Replace Window
 * - Issue #5: Stale SELL Window
 */
class Phase2IntegrationTest {
    private val client: EClientSocket = mockk(relaxed = true)
    private val openOrdersAdapter: IbkrOpenOrdersAdapter = mockk()

    private lateinit var orderCancellationService: OrderCancellationService
    private lateinit var orderReplacementService: OrderReplacementService

    fun setup() {
        orderCancellationService = OrderCancellationService(client, openOrdersAdapter)
        orderReplacementService = OrderReplacementService(orderCancellationService, openOrdersAdapter)
    }

    @Test
    fun `issue 5 - multiple stale orders cancelled atomically`() =
        runTest {
            setup()

            // Simulates multiple stale orders from failed operations
            val staleBuyOrder =
                OpenOrder(
                    orderId = 301,
                    symbol = "QQQ",
                    action = "BUY",
                    orderType = "LMT",
                    limitPrice = 380.0,
                    status = "Submitted",
                )

            val staleSellOrder =
                OpenOrder(
                    orderId = 302,
                    symbol = "QQQ",
                    action = "SELL",
                    orderType = "LMT",
                    limitPrice = 375.0,
                    status = "Submitted",
                )

            // Both orders initially present, then removed after cancel
            coEvery { openOrdersAdapter.getOpenOrders() }
                .coAnswers { listOf(staleBuyOrder, staleSellOrder) }
                .andThen { emptyList() }

            val results =
                orderCancellationService.cancelOrdersAtomic(
                    listOf(301, 302),
                    "test_multiple",
                )

            assertTrue(results.all { it.success })
            verify { client.cancelOrder(301, any<OrderCancel>()) }
            verify { client.cancelOrder(302, any<OrderCancel>()) }
        }

    @Test
    fun `issue 5 - already filled orders skipped`() =
        runTest {
            setup()

            val filledOrder =
                OpenOrder(
                    orderId = 401,
                    symbol = "XYZ",
                    action = "SELL",
                    orderType = "LMT",
                    limitPrice = 100.0,
                    status = "Filled",
                )

            coEvery { openOrdersAdapter.getOpenOrders() }
                .returns(listOf(filledOrder))

            val results = orderCancellationService.cancelOrdersAtomic(listOf(401), "test_filled")

            assertTrue(results[0].success)
            verify(exactly = 0) { client.cancelOrder(any(), any<OrderCancel>()) }
        }

    @Test
    fun `issue 3 - order replacement verifies old order removal`() =
        runTest {
            setup()

            val orderId = 200
            val existingOrder =
                OpenOrder(
                    orderId = orderId,
                    symbol = "MSFT",
                    action = "SELL",
                    orderType = "LMT",
                    limitPrice = 350.0,
                    status = "Submitted",
                )

            // Order present initially, then removed after cancel
            coEvery { openOrdersAdapter.getOpenOrders() }
                .coAnswers { listOf(existingOrder) }
                .andThen { emptyList() }

            val result = orderReplacementService.replacementCancel(orderId)

            assertTrue(result)
            verify { client.cancelOrder(orderId, any<OrderCancel>()) }
        }

    @Test
    fun `issue 3 - order replacement fails gracefully on verification timeout`() =
        runTest {
            setup()

            val orderId = 201
            val existingOrder =
                OpenOrder(
                    orderId = orderId,
                    symbol = "GOOG",
                    action = "SELL",
                    orderType = "LMT",
                    limitPrice = 140.0,
                    status = "Submitted",
                )

            // Order never removed (simulates network issue)
            coEvery { openOrdersAdapter.getOpenOrders() }
                .returns(listOf(existingOrder))

            val result = orderReplacementService.replacementCancel(orderId)

            // Should return false but not throw
            assertFalse(result)
        }

    @Test
    fun `issue 5 - partial cancellation on mixed status orders`() =
        runTest {
            setup()

            val pendingOrder =
                OpenOrder(
                    orderId = 501,
                    symbol = "AMD",
                    action = "SELL",
                    orderType = "LMT",
                    limitPrice = 45.5,
                    status = "Submitted",
                )

            val filledOrder =
                OpenOrder(
                    orderId = 502,
                    symbol = "AMD",
                    action = "BUY",
                    orderType = "LMT",
                    limitPrice = 50.0,
                    status = "Filled",
                )

            coEvery { openOrdersAdapter.getOpenOrders() }
                .coAnswers { listOf(pendingOrder, filledOrder) }
                .andThen { listOf(filledOrder) } // Pending order cancelled

            val results =
                orderCancellationService.cancelOrdersAtomic(
                    listOf(501, 502),
                    "test_mixed",
                )

            assertTrue(results.all { it.success })
            // Only the pending order should get a cancel request
            verify(exactly = 1) { client.cancelOrder(any(), any<OrderCancel>()) }
        }
}
