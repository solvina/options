package cz.solvina.options.adapters.outbound.ibkr.registry

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for the streaming-mode completion path.
 *
 * reqMktData(snapshot=false) never emits tickSnapshotEnd, so before this fix the pending deferred
 * could only resolve on timeout — returning an empty (all-NaN) snapshot that forced every option
 * quote into the Black-Scholes "synthetic" fallback, and no spread was ever launched. The registry
 * must now resolve the request as soon as the caller-supplied readiness predicate is satisfied.
 */
class IbkrMarketDataRegistryTest {
    private val idCounter = mockk<IbkrIdCounter>().also { every { it.next() } returns 42 }
    private val registry = IbkrMarketDataRegistry(idCounter)

    private val optionReady: (MarketDataSnapshot) -> Boolean =
        { !it.bid.isNaN() && !it.ask.isNaN() && !it.delta.isNaN() }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `streaming request resolves on tick arrival without tickSnapshotEnd`() {
        val reqId = 42
        val deferred = CompletableDeferred<MarketDataSnapshot>()
        registry.pendingMarketData[reqId] = PendingMarketDataRequest(deferred, optionReady)

        // Ticks arrive incrementally; IBKR sends no tickSnapshotEnd in streaming mode.
        registry.onTickPrice(reqId, field = 1, price = 2.41) // bid
        assertFalse(deferred.isCompleted, "must not resolve before ask and delta arrive")

        registry.onTickPrice(reqId, field = 2, price = 2.55) // ask
        assertFalse(deferred.isCompleted, "must not resolve before delta arrives")

        registry.onTickOptionComputation(reqId, field = 13, impliedVol = 0.64, delta = -0.12, gamma = 0.003, vega = 0.20, theta = -0.14)

        assertTrue(deferred.isCompleted, "should resolve once bid, ask and delta are all present")
        val snapshot = deferred.getCompleted()
        assertEquals(2.41, snapshot.bid)
        assertEquals(2.55, snapshot.ask)
        assertEquals(-0.12, snapshot.delta)
        // Request is removed from the pending map once resolved.
        assertFalse(registry.pendingMarketData.containsKey(reqId))
    }

    @Test
    fun `option greeks sentinel value does not satisfy readiness`() {
        val reqId = 42
        val deferred = CompletableDeferred<MarketDataSnapshot>()
        registry.pendingMarketData[reqId] = PendingMarketDataRequest(deferred, optionReady)

        registry.onTickPrice(reqId, field = 1, price = 2.41)
        registry.onTickPrice(reqId, field = 2, price = 2.55)
        // IBKR's "not computed yet" sentinel (Double.MAX_VALUE) must be ignored, leaving delta NaN.
        registry.onTickOptionComputation(
            reqId,
            field = 13,
            impliedVol = Double.MAX_VALUE,
            delta = Double.MAX_VALUE,
            gamma = Double.MAX_VALUE,
            vega = Double.MAX_VALUE,
            theta = Double.MAX_VALUE,
        )

        assertFalse(deferred.isCompleted, "sentinel greeks must not be treated as a live delta")
    }
}
