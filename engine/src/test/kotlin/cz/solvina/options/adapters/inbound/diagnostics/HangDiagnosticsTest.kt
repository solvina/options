package cz.solvina.options.adapters.inbound.diagnostics

import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOrdersRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertTrue

class HangDiagnosticsTest {
    private val ordersRegistry = mockk<IbkrOrdersRegistry>()
    private val diagnostics = HangDiagnostics(ordersRegistry)

    @Test
    fun `report lists outstanding waiters and always includes a thread dump`() {
        every { ordersRegistry.outstandingWaiters(any()) } returns
            listOf(
                IbkrOrdersRegistry.OutstandingWaiter(
                    orderId = 4210,
                    since = Instant.now().minusSeconds(900),
                    ageMs = 900_000,
                    lastStatus = "PRESUBMITTED",
                    symbol = "AAPL",
                ),
            )

        val report = diagnostics.buildReport("unit-test")

        assertTrue("Outstanding order-fill waits (1)" in report, "should list the stuck waiter count")
        assertTrue("orderId=4210" in report && "AAPL" in report, "should name the stuck order")
        assertTrue("---- Thread dump ----" in report, "thread dump section must always be present")
        // DebugProbes is not installed in the test JVM, so the coroutine section notes that.
        assertTrue("Coroutine dump" in report, "coroutine dump section must be present")
    }
}
