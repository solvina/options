package cz.solvina.options.adapters.outbound.ibkr.registry

import com.ib.client.Contract
import com.ib.client.ContractDetails
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

class IbkrContractDetailsRegistryTest {
    private val registry = IbkrContractDetailsRegistry()

    @Test
    fun `contractDetails before waiter is observed`() =
        runTest {
            registry.startRequest(10, "ASML stock")
            val details = contractDetails(conId = 123)

            registry.onContractDetails(10, details)

            assertEquals(listOf(details), registry.current(10)?.details?.toList())
        }

    @Test
    fun `contractDetailsEnd before waiter returns cached terminal state`() =
        runTest {
            registry.startRequest(11, "ASML stock")
            val details = contractDetails(conId = 456)
            registry.onContractDetails(11, details)
            registry.onContractDetailsEnd(11)

            val result = registry.awaitEnd(11, 10.milliseconds)

            assertEquals(listOf(details), result)
            assertEquals(ContractRequestStatus.COMPLETED, registry.current(11)?.status)
        }

    @Test
    fun `timeout stops waiting but does not remove request state`() =
        runTest {
            registry.startRequest(12, "slow contract")

            assertFailsWith<TimeoutCancellationException> {
                registry.awaitEnd(12, 10.milliseconds)
            }

            val state = assertNotNull(registry.current(12))
            assertEquals(ContractRequestStatus.ACTIVE, state.status)
            assertFalse(state.terminal)
        }

    @Test
    fun `late TWS response after caller timeout is stored and usable`() =
        runTest {
            registry.startRequest(13, "late contract")
            assertFailsWith<TimeoutCancellationException> {
                registry.awaitEnd(13, 10.milliseconds)
            }

            val details = contractDetails(conId = 789)
            registry.onContractDetails(13, details)
            registry.onContractDetailsEnd(13)

            assertEquals(listOf(details), registry.awaitEnd(13, 10.milliseconds))
        }

    @Test
    fun `error 200 marks only matching request failed`() =
        runTest {
            registry.startRequest(14, "missing contract")
            registry.startRequest(15, "other contract")

            registry.onError(14, 200, "No security definition")

            assertEquals(ContractRequestStatus.FAILED, registry.current(14)?.status)
            assertEquals(ContractRequestStatus.ACTIVE, registry.current(15)?.status)
            assertFailsWith<RuntimeException> {
                registry.awaitEnd(14, 10.milliseconds)
            }
        }

    @Test
    fun `disconnect marks active requests failed without faking empty success`() =
        runTest {
            registry.startRequest(16, "active contract")

            registry.cancelAllPending(RuntimeException("IBKR disconnected"))

            assertEquals(ContractRequestStatus.FAILED, registry.current(16)?.status)
            assertFailsWith<RuntimeException> {
                registry.awaitEnd(16, 10.milliseconds)
            }
        }

    private fun contractDetails(conId: Int): ContractDetails =
        ContractDetails().apply {
            contract(
                Contract().apply {
                    symbol("ASML")
                    secType("STK")
                    exchange("AEB")
                    currency("EUR")
                    conid(conId)
                },
            )
        }
}
