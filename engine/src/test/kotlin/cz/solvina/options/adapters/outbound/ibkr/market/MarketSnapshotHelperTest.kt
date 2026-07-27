package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Contract
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderIdCounter
import cz.solvina.options.adapters.outbound.ibkr.registry.MarketDataSnapshot
import cz.solvina.options.domain.models.Symbol
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for MarketSnapshotHelper.reqMktDataSnapshot — "the one place every snapshot
 * flows through" per its own comment. The registry removes a pending request as a side effect of
 * both normal completion (completeIfReady, the common fast-completion path) and graceful error
 * handling (onError, codes 200/354/10197/10168 — all documented as expected/frequent on
 * delayed/paper accounts). The helper's own `finally { registry.remove(reqId) }` cleanup must not
 * fight that: it must return the assembled snapshot (and still call cancelMktData) instead of
 * throwing, on both paths.
 */
class MarketSnapshotHelperTest {
    private val reqId = 42
    private val idCounter = mockk<IbkrOrderIdCounter> { every { nextOrderId() } returns reqId }
    private val registry = IbkrMarketDataRegistry(idCounter)
    private val client = mockk<EClientSocket>(relaxed = true)
    private val contract = mockk<Contract>(relaxed = true)
    private val symbol = Symbol("AAPL")
    private val helper = MarketSnapshotHelper(registry, idCounter, client)

    private val optionReady: (MarketDataSnapshot) -> Boolean =
        { !it.bid.isNaN() && !it.ask.isNaN() && !it.delta.isNaN() }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `reqMktDataSnapshot returns a snapshot instead of throwing when isReady completes fast`() =
        runTest {
            val result = async { helper.reqMktDataSnapshot(symbol, contract, isReady = optionReady) }
            runCurrent()

            // Ticks satisfy optionReady, so the registry's completeIfReady() resolves AND removes
            // the pending request itself — before the helper's own finally block runs its cleanup.
            registry.onTickPrice(reqId, field = 1, price = 2.41) // bid
            registry.onTickPrice(reqId, field = 2, price = 2.55) // ask
            registry.onTickOptionComputation(
                reqId,
                field = 13,
                impliedVol = 0.64,
                delta = -0.12,
                gamma = 0.003,
                vega = 0.20,
                theta = -0.14,
            )

            val snapshot = result.await()
            assertEquals(2.41, snapshot.bid)
            assertEquals(2.55, snapshot.ask)
            assertEquals(-0.12, snapshot.delta)
            verify { client.cancelMktData(reqId) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `reqMktDataSnapshot returns the partial snapshot instead of throwing on graceful-degradation error codes`() =
        runTest {
            val result = async { helper.reqMktDataSnapshot(symbol, contract, isReady = optionReady) }
            runCurrent()

            // 354 = no live subscription — IBKR does not send tickSnapshotEnd for it, so the
            // registry completes (and removes) the deferred itself from onError.
            registry.onError(reqId, code = 354, msg = "no live subscription")

            val snapshot = result.await()
            assertEquals(true, snapshot.bid.isNaN(), "no ticks arrived before the graceful-degradation error")
            verify { client.cancelMktData(reqId) }
        }
}
