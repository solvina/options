package cz.solvina.options.adapters.outbound.ibkr

import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrNewsRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrNewsRow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IbkrNewsRegistryTest {
    private val registry = IbkrNewsRegistry()

    @Test
    fun `headlines collect until the end message, and an error fails only its own request`() =
        runTest {
            val first = registry.startHeadlines(1)
            val second = registry.startHeadlines(2)
            registry.onHeadline(1, IbkrNewsRow("2026-11-19 13:00:00.0", "BRFG", "a1", "one"))
            registry.onHeadline(1, IbkrNewsRow("2026-11-19 14:00:00.0", "BRFG", "a2", "two"))
            registry.onHeadlinesEnd(1)
            registry.onError(2, 10197, "No market data during competing live session")

            assertEquals(listOf("one", "two"), first.await().map { it.headline })
            assertFailsWith<RuntimeException> { second.await() }
        }

    @Test
    fun `providers are asked once, and again after a disconnect failed them`() {
        assertTrue(registry.startProviders())
        assertFalse(registry.startProviders(), "in flight")
        registry.cancelAllPending(IllegalStateException("disconnected"))
        assertTrue(registry.startProviders(), "the failed answer is replaced")
        registry.onProviders(emptyList())
        assertFalse(registry.startProviders(), "answered")
    }
}
