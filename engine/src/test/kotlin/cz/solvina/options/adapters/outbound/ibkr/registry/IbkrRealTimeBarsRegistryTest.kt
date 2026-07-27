package cz.solvina.options.adapters.outbound.ibkr.registry

import cz.solvina.options.domain.features.bars.RealTimeBar
import cz.solvina.options.domain.models.Symbol
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression coverage for real-time-bar error routing.
 *
 * A rejected reqRealTimeBars subscription (competing session, error 456 max real-time bars, no
 * live-data subscription) is otherwise indistinguishable from a quiet market — without onError
 * reaching the subscriber, the flag strategy goes silently blind with zero alert. This registry
 * was split out of IbkrMarketDataRegistry, which used to special-case this routing before any
 * generic (and possibly graceful) error handling; onError here must preserve that behaviour.
 */
class IbkrRealTimeBarsRegistryTest {
    private val symbol = Symbol("AAPL")

    @Test
    fun `onError invokes the matching request's onError callback`() {
        val registry = IbkrRealTimeBarsRegistry()
        val reqId = 7
        var receivedCode: Int? = null
        var receivedMsg: String? = null

        registry.addRealTimeBarRequest(
            reqId,
            symbol,
            PendingRealTimeBarsRequest(
                onBar = {},
                onError = { code, msg ->
                    receivedCode = code
                    receivedMsg = msg
                },
            ),
        )

        registry.onError(reqId, code = 354, msg = "no live subscription")

        assertEquals(354, receivedCode)
        assertEquals("no live subscription", receivedMsg)
    }

    @Test
    fun `onError for an unknown reqId does not throw`() {
        val registry = IbkrRealTimeBarsRegistry()

        registry.onError(999, code = 456, msg = "max real time bars requests")
    }

    @Test
    fun `onError does not fire the onBar callback`() {
        val registry = IbkrRealTimeBarsRegistry()
        val reqId = 7
        var barReceived: RealTimeBar? = null

        registry.addRealTimeBarRequest(
            reqId,
            symbol,
            PendingRealTimeBarsRequest(onBar = { barReceived = it }, onError = { _, _ -> }),
        )

        registry.onError(reqId, code = 354, msg = "no live subscription")

        assertNull(barReceived)
    }
}
