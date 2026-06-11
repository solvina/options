package cz.solvina.options.adapters.outbound.ibkr.order

import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.account.OpenOrder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderReplacementServiceTest {
    private val openOrdersAdapter: IbkrOpenOrdersAdapter = mockk()
    private val orderCancellationService: OrderCancellationService = mockk()
    private val service = OrderReplacementService(orderCancellationService, openOrdersAdapter)

    @Test
    fun `order replacement waits indefinitely for cancel confirmation`() = runTest {
        val orderId = 123

        // Order present on first calls, then removed
        coEvery { openOrdersAdapter.getOpenOrders() }
            .coAnswers {
                OpenOrder(orderId, "AMD", "SELL", "LMT", 45.5, "Submitted")
                    .let { listOf(it) }
            }
            .andThenAnswer {
                OpenOrder(orderId, "AMD", "SELL", "LMT", 45.5, "Submitted")
                    .let { listOf(it) }
            }
            .andThenAnswer { emptyList() }

        val result = service.verifyOrderRemoved(orderId)

        assertTrue(result)
    }

    @Test
    fun `replacement cancel uses atomic cancellation service`() = runTest {
        val orderId = 456

        coEvery {
            orderCancellationService.cancelOrdersAtomic(
                listOf(orderId),
                "order_replacement",
            )
        }.returns(
            listOf(
                OrderCancellationService.CancellationResult(
                    orderId = orderId,
                    success = true,
                    reason = "verified_removed",
                    attemptCount = 2,
                )
            )
        )

        val result = service.replacementCancel(orderId)

        assertTrue(result)
        coVerify {
            orderCancellationService.cancelOrdersAtomic(
                listOf(orderId),
                "order_replacement",
            )
        }
    }

    @Test
    fun `replacement cancel falls back to direct verification if service returns false`() = runTest {
        val orderId = 789

        coEvery {
            orderCancellationService.cancelOrdersAtomic(
                listOf(orderId),
                "order_replacement",
            )
        }.returns(
            listOf(
                OrderCancellationService.CancellationResult(
                    orderId = orderId,
                    success = false,
                    reason = "verification_timeout_after_5_attempts",
                    attemptCount = 5,
                )
            )
        )

        // Direct verification succeeds
        coEvery { openOrdersAdapter.getOpenOrders() }
            .returns(emptyList())

        val result = service.replacementCancel(orderId)

        assertTrue(result)
    }

    @Test
    fun `verification fails if order not removed after retries`() = runTest {
        val orderId = 999
        val openOrder = OpenOrder(orderId, "GOOG", "SELL", "LMT", 140.0, "Submitted")

        // Order always present (never removed)
        coEvery { openOrdersAdapter.getOpenOrders() }
            .returns(listOf(openOrder))

        val result = service.verifyOrderRemoved(orderId)

        assertFalse(result)
    }

    @Test
    fun `cancellation confirmed on first retry`() = runTest {
        val orderId = 111

        // First call returns order, then removed
        coEvery { openOrdersAdapter.getOpenOrders() }
            .coAnswers { listOf(OpenOrder(orderId, "TSLA", "BUY", "LMT", 250.0, "Submitted")) }
            .andThen { emptyList() }

        val result = service.verifyOrderRemoved(orderId)

        assertTrue(result)
    }
}
