package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.account.OpenOrder
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Absence from IBKR's open-orders list proves an order is no longer WORKING, but that is true both
 * when it was cancelled AND when it FILLED in the race window between the cancel request and its
 * confirmation. [OrderReplacementService] must consult [IbkrOrderRegistry.isFilled] (populated from
 * the authoritative orderStatus callback) to tell the two apart, so a caller never submits a
 * replacement for an order that actually filled (which would double the position).
 */
class OrderReplacementServiceTest {
    private val client: EClientSocket = mockk(relaxed = true)
    private val openOrdersAdapter: IbkrOpenOrdersAdapter = mockk()
    private val orderRegistry: IbkrOrderRegistry = mockk()
    private val cancellationService = OrderCancellationService(client, openOrdersAdapter)
    private val service = OrderReplacementService(cancellationService, openOrdersAdapter, orderRegistry)

    private fun openOrder(orderId: Int) =
        OpenOrder(orderId = orderId, symbol = "SPY", action = "SELL", orderType = "LMT", limitPrice = 1.0, status = "Submitted")

    @Test
    fun `order absent and not filled - Removed, safe to replace`() =
        runTest {
            val orderId = 100
            coEvery { openOrdersAdapter.getOpenOrders() }
                .coAnswers { listOf(openOrder(orderId)) }
                .andThen { emptyList() }
            every { orderRegistry.isFilled(orderId) } returns false

            val result = service.replacementCancel(orderId)

            assertEquals(ReplacementCancelResult.Removed, result)
        }

    @Test
    fun `order filled during the cancel race - Filled, must not replace`() =
        runTest {
            val orderId = 200
            // The order disappears from open-orders (as a filled order would), but the registry
            // knows — from the orderStatus callback — that it filled rather than being cancelled.
            coEvery { openOrdersAdapter.getOpenOrders() }
                .coAnswers { listOf(openOrder(orderId)) }
                .andThen { emptyList() }
            every { orderRegistry.isFilled(orderId) } returns true

            val result = service.replacementCancel(orderId)

            assertEquals(ReplacementCancelResult.Filled, result)
        }

    @Test
    fun `order still present after all retries - Unverified`() =
        runTest {
            val orderId = 300
            coEvery { openOrdersAdapter.getOpenOrders() }.returns(listOf(openOrder(orderId)))
            every { orderRegistry.isFilled(orderId) } returns false

            val result = service.replacementCancel(orderId)

            assertEquals(ReplacementCancelResult.Unverified, result)
        }
}
