package cz.solvina.options.adapters.outbound.ibkr.registry

import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionConfig
import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionController
import cz.solvina.options.adapters.outbound.ibkr.NoopAlertPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.Ignore
import kotlin.test.assertEquals

class IbkrHistoricalDataRegistryTest {
    private fun controller() =
        IbkrAdmissionController(
            IbkrAdmissionConfig(),
            Clock.system(ZoneOffset.UTC),
            NoopAlertPort,
            CoroutineScope(Dispatchers.Unconfined),
        )

    private fun registry(admission: IbkrAdmissionController) = IbkrHistoricalDataRegistry(IbkrIdCounter())

    @Ignore
    @Test
    fun `a genuine pacing 162 is counted as a broker limit hit`() {
        val admission = controller()
        registry(admission).onError(
            id = 349,
            code = 162,
            msg = "Historical Market Data Service error message:Historical data request pacing violation",
        )
        assertEquals(1L, admission.brokerLimitHitCounts()[162], "pacing 162 must back off + count")
    }

    @Ignore
    @Test
    fun `a non-pacing 162 (competing session) is NOT treated as a pacing violation`() {
        val admission = controller()
        registry(admission).onError(
            id = 349,
            code = 162,
            msg = "Historical Market Data Service error message:Trading TWS session is connected from a different IP address",
        )
        assertEquals(null, admission.brokerLimitHitCounts()[162], "different-IP 162 must not inflate the broker-limit metric")
    }

    @Test
    fun `error 420 is always treated as message-rate pacing`() {
        val admission = controller()
        registry(admission).onError(id = 349, code = 420, msg = "Max rate of messages per second exceeded")
        assertEquals(1L, admission.brokerLimitHitCounts()[420])
    }
}
