package cz.solvina.options.adapters.outbound.ibkr.registry

import com.ib.client.PriceIncrement
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class IbkrMarketRuleRegistryTest {
    private val registry = IbkrMarketRuleRegistry()

    @Test
    fun `market rule request deduplicates by marketRuleId`() =
        runTest {
            assertTrue(registry.startRequest(26))
            assertFalse(registry.startRequest(26))

            val increments = listOf(PriceIncrement(0.0, 0.05))
            registry.onMarketRule(26, increments)

            assertFalse(registry.startRequest(26))
            assertSame(increments, registry.await(26, 10.milliseconds))
            assertNull(registry.current(27))
        }
}
