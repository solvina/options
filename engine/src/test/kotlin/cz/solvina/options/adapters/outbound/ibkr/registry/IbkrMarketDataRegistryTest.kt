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

    /**
     * Delayed mode (reqMarketDataType(3)) delivers the same callbacks under delayed tick IDs:
     * bid/ask/last/close arrive as 66/67/68/75 and option computations as 80–83. Before the
     * normalization these fell through the field filters, so no delayed snapshot ever completed and
     * every quote degraded to the Black-Scholes synthetic fallback.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `delayed tick fields resolve an option quote request`() {
        val reqId = 42
        val deferred = CompletableDeferred<MarketDataSnapshot>()
        registry.pendingMarketData[reqId] = PendingMarketDataRequest(deferred, optionReady)

        registry.onTickPrice(reqId, field = 66, price = 2.41) // DELAYED_BID
        registry.onTickPrice(reqId, field = 67, price = 2.55) // DELAYED_ASK
        assertFalse(deferred.isCompleted, "must not resolve before delayed greeks arrive")

        // DELAYED_MODEL_OPTION_COMPUTATION
        registry.onTickOptionComputation(reqId, field = 83, impliedVol = 0.64, delta = -0.12, gamma = 0.003, vega = 0.20, theta = -0.14)

        assertTrue(deferred.isCompleted, "delayed bid/ask/greeks must complete the snapshot")
        val snapshot = deferred.getCompleted()
        assertEquals(2.41, snapshot.bid)
        assertEquals(2.55, snapshot.ask)
        assertEquals(-0.12, snapshot.delta)
    }

    @Test
    fun `delayed last and close ticks update a continuous stream`() {
        val reqId = 42
        var latest: MarketDataSnapshot? = null
        registry.pendingContinuousMarketData[reqId] = PendingContinuousMarketDataRequest(onUpdate = { latest = it })

        registry.onTickPrice(reqId, field = 68, price = 101.5) // DELAYED_LAST
        assertEquals(101.5, latest?.last)

        registry.onTickPrice(reqId, field = 75, price = 99.0) // DELAYED_CLOSE
        assertEquals(99.0, latest?.close)
    }
}
