package cz.solvina.options.adapters.outbound.ibkr.registry

import cz.solvina.options.domain.models.Symbol
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    private val idCounter = mockk<IbkrOrderIdCounter>().also { every { it.nextOrderId() } returns 42 }
    private val registry = IbkrMarketDataRegistry(idCounter)
    private val symbol = Symbol("AAPL")

    private val optionReady: (MarketDataSnapshot) -> Boolean =
        { !it.bid.isNaN() && !it.ask.isNaN() && !it.delta.isNaN() }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `streaming request resolves on tick arrival without tickSnapshotEnd`() {
        val reqId = 42
        val request = registry.createPendingMarketDataRequest(reqId, symbol, isReady = optionReady)

        // Ticks arrive incrementally; IBKR sends no tickSnapshotEnd in streaming mode.
        registry.onTickPrice(reqId, field = 1, price = 2.41) // bid
        assertFalse(request.deferred.isCompleted, "must not resolve before ask and delta arrive")

        registry.onTickPrice(reqId, field = 2, price = 2.55) // ask
        assertFalse(request.deferred.isCompleted, "must not resolve before delta arrives")

        registry.onTickOptionComputation(reqId, field = 13, impliedVol = 0.64, delta = -0.12, gamma = 0.003, vega = 0.20, theta = -0.14)

        assertTrue(request.deferred.isCompleted, "should resolve once bid, ask and delta are all present")
        val snapshot = request.deferred.getCompleted()
        assertEquals(2.41, snapshot.bid)
        assertEquals(2.55, snapshot.ask)
        assertEquals(-0.12, snapshot.delta)
        // Request is removed from the pending map once resolved.
        assertFalse(registry.hasReqId(reqId))
    }

    @Test
    fun `option greeks sentinel value does not satisfy readiness`() {
        val reqId = 42
        val deferred = CompletableDeferred<MarketDataSnapshot>()
        registry.createPendingMarketDataRequest(reqId, symbol, isReady = optionReady)

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
        val request = registry.createPendingMarketDataRequest(reqId, symbol, isReady = optionReady)

        registry.onTickPrice(reqId, field = 66, price = 2.41) // DELAYED_BID
        registry.onTickPrice(reqId, field = 67, price = 2.55) // DELAYED_ASK
        assertFalse(request.deferred.isCompleted, "must not resolve before delayed greeks arrive")

        // DELAYED_MODEL_OPTION_COMPUTATION
        registry.onTickOptionComputation(reqId, field = 83, impliedVol = 0.64, delta = -0.12, gamma = 0.003, vega = 0.20, theta = -0.14)

        assertTrue(request.deferred.isCompleted, "delayed bid/ask/greeks must complete the snapshot")
        val snapshot = request.deferred.getCompleted()
        assertEquals(2.41, snapshot.bid)
        assertEquals(2.55, snapshot.ask)
        assertEquals(-0.12, snapshot.delta)
    }

    @Test
    fun `delayed last and close ticks update a continuous stream`() {
        val reqId = 42
        var latest: MarketDataSnapshot? = null
        registry.addPendingContinuousMarketDataRequest(reqId, symbol, onUpdate = { latest = it })

        registry.onTickPrice(reqId, field = 68, price = 101.5) // DELAYED_LAST
        assertEquals(101.5, latest?.last)

        registry.onTickPrice(reqId, field = 75, price = 99.0) // DELAYED_CLOSE
        assertEquals(99.0, latest?.close)
    }

    /**
     * Regression coverage for the throwing remove(reqId): the registry itself removes entries as
     * a side effect of normal completion (completeIfReady) and of graceful error handling
     * (onError), racing against every call site's own cleanup (e.g. MarketSnapshotHelper's
     * finally block) which also calls remove(reqId) assuming the old silent-no-op semantics.
     * A second remove() for an id the registry already resolved must not throw.
     */
    @Test
    fun `remove() after completeIfReady already resolved the request does not throw`() {
        val reqId = 42
        registry.createPendingMarketDataRequest(reqId, symbol, isReady = optionReady)

        // Satisfies optionReady, so completeIfReady() resolves and removes the entry itself.
        registry.onTickPrice(reqId, field = 1, price = 2.41) // bid
        registry.onTickPrice(reqId, field = 2, price = 2.55) // ask
        registry.onTickOptionComputation(reqId, field = 13, impliedVol = 0.64, delta = -0.12, gamma = 0.003, vega = 0.20, theta = -0.14)
        assertFalse(registry.hasReqId(reqId), "completeIfReady should already have removed the entry")

        // Caller-side cleanup (e.g. MarketSnapshotHelper's `finally { registry.remove(reqId) }`)
        // runs anyway. Must be a harmless no-op, not an exception.
        registry.remove(reqId)
    }

    @Test
    fun `remove() after onError already resolved the request does not throw`() {
        val reqId = 42
        registry.createPendingMarketDataRequest(reqId, symbol, isReady = optionReady)

        // Graceful-degradation codes (200/354/10197/10168) resolve and remove pendingMarketData
        // themselves — this is the documented, expected path for delayed/paper accounts.
        registry.onError(reqId, code = 354, msg = "no live subscription")
        assertFalse(registry.hasReqId(reqId), "onError should already have removed the entry")

        registry.remove(reqId)
    }

    /**
     * Leak regression: every registration writes reqIdToSymbol (so the marketDataType callback can
     * attribute a feed type to a symbol), but remove() is the single cleanup choke point all
     * lifecycle paths funnel through. If it doesn't retire the symbol entry, the map grows one
     * entry per snapshot/stream for the life of the process. Covers all three registration paths.
     */
    @Test
    fun `remove() retires the reqId to symbol attribution for a snapshot request`() {
        val reqId = 42
        registry.createPendingMarketDataRequest(reqId, symbol, isReady = optionReady)
        assertEquals(symbol, registry.getSymbolForMarketData(reqId))

        registry.remove(reqId)
        assertNull(registry.getSymbolForMarketData(reqId), "snapshot symbol attribution must not leak")
    }

    @Test
    fun `remove() retires the reqId to symbol attribution for a continuous stream`() {
        val reqId = 42
        registry.addPendingContinuousMarketDataRequest(reqId, symbol, onUpdate = {})
        assertEquals(symbol, registry.getSymbolForMarketData(reqId))

        registry.remove(reqId)
        assertNull(registry.getSymbolForMarketData(reqId), "continuous-stream symbol attribution must not leak")
    }

    @Test
    fun `remove() retires the reqId to symbol attribution for a tick-by-tick request`() {
        val reqId = 42
        registry.addPendingTickByTickRequest(reqId, symbol, PendingTickByTickRequest { true })
        assertEquals(symbol, registry.getSymbolForMarketData(reqId))

        registry.remove(reqId)
        assertNull(registry.getSymbolForMarketData(reqId), "tick-by-tick symbol attribution must not leak")
    }

    @Test
    fun `cancelAllPending clears symbol attributions along with pending requests`() {
        registry.createPendingMarketDataRequest(1, symbol, isReady = optionReady)
        registry.addPendingContinuousMarketDataRequest(2, symbol, onUpdate = {})
        registry.addPendingTickByTickRequest(3, symbol, PendingTickByTickRequest { true })

        registry.cancelAllPending(RuntimeException("disconnect"))

        assertNull(registry.getSymbolForMarketData(1))
        assertNull(registry.getSymbolForMarketData(2))
        assertNull(registry.getSymbolForMarketData(3))
    }
}
