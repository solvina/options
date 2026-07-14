package cz.solvina.options.adapters.outbound.ibkr.market

import cz.solvina.options.adapters.outbound.ibkr.registry.MarketDataSnapshot
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingMarketDataRequest
import cz.solvina.options.domain.features.market.MarketDataPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AwaitSnapshotTest {
    // asOf starts at EPOCH so a later real-clock tick is unambiguously "advanced past the start".
    private fun pending(): PendingMarketDataRequest =
        PendingMarketDataRequest(CompletableDeferred(), { false }, MarketDataSnapshot(asOf = Instant.EPOCH))

    @Test
    fun `returns immediately when the readiness predicate completes the deferred`() =
        runBlocking {
            val p = pending()
            p.deferred.complete(MarketDataSnapshot(bid = 1.0, ask = 1.1, delta = -0.3))
            val result = withTimeout(1_000) { awaitSnapshot(p.deferred, p, MarketDataPriority.SCANNER, quiescenceMs = 100) }
            assertEquals(-0.3, result.delta)
        }

    @Test
    fun `SCANNER bails soon after the quote goes quiet without greeks`() =
        runBlocking {
            val p = pending()
            val call = async { awaitSnapshot(p.deferred, p, MarketDataPriority.SCANNER, quiescenceMs = 100) }
            delay(30)
            // A quote tick lands (bid/ask) but the option-computation (delta) tick never arrives.
            p.snapshot = p.snapshot.copy(bid = 1.0, ask = 1.1, asOf = Instant.now())
            val start = System.nanoTime()
            val result = withTimeout(2_000) { call.await() }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(result.delta.isNaN(), "greeks never arrived → partial with NaN delta")
            assertEquals(1.0, result.bid)
            assertTrue(elapsedMs < 1_000, "should return shortly after quiescence, not the 2s hard ceiling (took ${elapsedMs}ms)")
        }

    @Test
    fun `SCANNER keeps waiting while no tick has arrived at all`() {
        runBlocking {
            val p = pending() // asOf never advances, deferred never completes
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(300) { awaitSnapshot(p.deferred, p, MarketDataPriority.SCANNER, quiescenceMs = 50) }
            }
        }
    }

    @Test
    fun `reserved priority ignores quiescence and waits for the full snapshot`() =
        runBlocking {
            val p = pending()
            val call = async { awaitSnapshot(p.deferred, p, MarketDataPriority.EXIT, quiescenceMs = 50) }
            // A partial quote lands, then the stream stays quiet well past the quiescence window.
            p.snapshot = p.snapshot.copy(bid = 1.0, asOf = Instant.now())
            delay(200)
            assertTrue(call.isActive, "EXIT must not bail on quiescence — still waiting for the predicate")
            p.deferred.complete(MarketDataSnapshot(bid = 1.0, ask = 1.1, delta = -0.3))
            assertEquals(-0.3, withTimeout(1_000) { call.await() }.delta)
        }
}
