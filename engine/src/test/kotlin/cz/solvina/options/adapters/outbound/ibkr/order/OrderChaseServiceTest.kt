package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.Contract
import com.ib.client.EClientSocket
import com.ib.client.Order
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.scanner.ScannerConfig
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The order chase reprices an unfilled leg order toward the marketable side on each retry: a SELL
 * must walk DOWN toward the bid, a BUY must walk UP toward the ask. Applying the SELL direction to a
 * BUY (e.g. the short leg's buy-back when closing a spread) would make the order progressively less
 * fillable on every retry instead of more.
 */
class OrderChaseServiceTest {
    private val client: EClientSocket = mockk(relaxed = true)
    private val registry: IbkrOrderRegistry = mockk()

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<cz.solvina.options.domain.features.order.OrderStatus>>()
    private val nextId = AtomicInteger(101)
    private val placedPrices = mutableListOf<Double>()

    private val config =
        ScannerConfig(
            orderChaseTimeoutMinutes = 1,
            orderChaseMaxRetries = 2,
            orderChasePriceStep = 0.10,
        )

    private val service = OrderChaseService(registry, client, config)

    @BeforeTest
    fun setup() {
        every { registry.nextOrderId() } answers { nextId.getAndIncrement() }
        every { registry.pendingOrderStatus } returns pending
        every { registry.markSelfCancelled(any()) } just Runs
        every { registry.isFilled(any()) } returns false
        every { client.placeOrder(any(), any(), any()) } answers {
            val order = thirdArg<Order>()
            placedPrices.add(order.lmtPrice())
        }
        // The initial order (id=100) sits unfilled for the whole test — never completed — so every
        // attempt times out and the chase reprices.
        pending[100] = CompletableDeferred()
    }

    @Test
    fun `chases BUY orders upward toward the ask`() =
        runTest {
            service.waitForFillOrChase(
                initialOrderId = 100,
                contract = Contract(),
                action = "BUY",
                initialPrice = BigDecimal("1.00"),
                qty = 1,
            )

            assertTrue(placedPrices.size >= 2, "expected at least 2 reprice attempts, got $placedPrices")
            assertTrue(placedPrices[0] > 1.00, "first BUY reprice must move UP from 1.00, got ${placedPrices[0]}")
            assertTrue(
                placedPrices[1] > placedPrices[0],
                "second BUY reprice must move further UP, got ${placedPrices[1]} after ${placedPrices[0]}",
            )
        }

    @Test
    fun `chases SELL orders downward toward the bid`() =
        runTest {
            service.waitForFillOrChase(
                initialOrderId = 100,
                contract = Contract(),
                action = "SELL",
                initialPrice = BigDecimal("1.00"),
                qty = 1,
            )

            assertTrue(placedPrices.size >= 2, "expected at least 2 reprice attempts, got $placedPrices")
            assertTrue(placedPrices[0] < 1.00, "first SELL reprice must move DOWN from 1.00, got ${placedPrices[0]}")
            assertTrue(
                placedPrices[1] < placedPrices[0],
                "second SELL reprice must move further DOWN, got ${placedPrices[1]} after ${placedPrices[0]}",
            )
        }

    @Test
    fun `a fill that races the cancel is honored instead of repriced - no duplicate leg order`() =
        runTest {
            // The chase timeout fires, the cancel is issued, but IBKR fills the order before
            // processing the cancel. Repricing would submit a SECOND order for the same leg and
            // double the position — the chase must return FILLED and place nothing further.
            every { registry.isFilled(100) } returns true

            val result =
                service.waitForFillOrChase(
                    initialOrderId = 100,
                    contract = Contract(),
                    action = "BUY",
                    initialPrice = BigDecimal("1.00"),
                    qty = 1,
                )

            assertEquals(cz.solvina.options.domain.features.order.OrderStatus.FILLED, result.status)
            assertEquals(100, result.orderId)
            assertTrue(placedPrices.isEmpty(), "no replacement order may be placed after a fill raced the cancel")
        }
}
