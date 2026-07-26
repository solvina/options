package cz.solvina.options.adapters.outbound.ibkr.registry

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class IbkrOptionParamsRegistryTest {
    private val registry = IbkrOptionParamsRegistry()

    @Test
    fun `option params rows aggregate by expiry and end returns params`() =
        runTest {
            registry.startRequest(17, "ASML", "EUREX")

            registry.onSecurityDefinitionOptionalParameter(
                reqId = 17,
                exchange = "EUREX",
                tradingClass = "OES",
                multiplier = "100",
                expirations = setOf("20260717", "20260821"),
                strikes = setOf(1300.0, 1320.0),
            )
            registry.onSecurityDefinitionOptionalParameterEnd(17)

            val params = registry.awaitEnd(17, 10.milliseconds)

            assertEquals(setOf(LocalDate.of(2026, 7, 17), LocalDate.of(2026, 8, 21)), params.expirations)
            assertEquals(setOf(BigDecimal("1300.0"), BigDecimal("1320.0")), params.strikes)
            assertEquals("EUREX", params.exchange)
            assertEquals("OES", params.tradingClass)
            assertEquals("100", params.multiplier)
        }

    @Test
    fun `disconnect marks active option params failed`() =
        runTest {
            registry.startRequest(17, "ASML", "EUREX")

            registry.cancelAllPending(RuntimeException("IBKR disconnected"))

            assertEquals(ContractRequestStatus.FAILED, registry.current(17)?.status)
            assertFailsWith<RuntimeException> {
                registry.awaitEnd(17, 10.milliseconds)
            }
        }
}
