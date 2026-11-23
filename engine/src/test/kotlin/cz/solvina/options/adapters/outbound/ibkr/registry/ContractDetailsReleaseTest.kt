package cz.solvina.options.adapters.outbound.ibkr.registry

import com.ib.client.ContractDetails
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ContractDetailsReleaseTest {
    private val registry = IbkrContractDetailsRegistry()

    @Test
    fun `a released request no longer holds its rows`() =
        runTest {
            registry.startRequest(7, "verified strikes QQQ")
            repeat(3) { registry.onContractDetails(7, ContractDetails()) }
            registry.onContractDetailsEnd(7)
            assertEquals(3, registry.awaitEnd(7, 1.seconds).size)

            registry.release(7)

            assertNull(registry.current(7))
            assertThrows<UnknownContractDetailsRequest> { registry.awaitEnd(7, 1.seconds) }
        }

    @Test
    fun `late rows for a released request are ignored`() {
        registry.startRequest(8, "option conId")
        registry.release(8)

        registry.onContractDetails(8, ContractDetails())
        registry.onContractDetailsEnd(8)

        assertNull(registry.current(8))
    }
}
