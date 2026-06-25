package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Contract
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.MarketDataSnapshot
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingMarketDataRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.math.RoundingMode

/** Readiness predicates for streaming [reqMktDataSnapshot] requests. A streaming subscription
 *  (snapshot=false) never emits tickSnapshotEnd, so the request must declare which fields make its
 *  snapshot "complete" — the registry resolves the deferred as soon as they have all arrived. */
internal object SnapshotReady {
    /** Underlying price: live last, else previous close. */
    val STOCK_PRICE: (MarketDataSnapshot) -> Boolean = { !it.last.isNaN() || !it.close.isNaN() }

    /** Option quote used for strike selection — needs both sides plus a live delta (greeks). */
    val OPTION_QUOTE: (MarketDataSnapshot) -> Boolean = { !it.bid.isNaN() && !it.ask.isNaN() && !it.delta.isNaN() }

    /** Option price only (mid for exits/repricing) — bid/ask are enough, greeks not required. */
    val OPTION_PRICE: (MarketDataSnapshot) -> Boolean = { !it.bid.isNaN() && !it.ask.isNaN() }
}

internal suspend fun reqMktDataSnapshot(
    registry: IbkrMarketDataRegistry,
    client: EClientSocket,
    contract: Contract,
    genericTickList: String,
    isReady: (MarketDataSnapshot) -> Boolean,
): MarketDataSnapshot {
    val reqId = registry.nextReqId()
    val deferred = CompletableDeferred<MarketDataSnapshot>()
    val pending = PendingMarketDataRequest(deferred, isReady)
    registry.pendingMarketData[reqId] = pending
    client.reqMktData(reqId, contract, genericTickList, false, false, null)
    return try {
        withTimeout(5_000L) { deferred.await() }
    } catch (_: TimeoutCancellationException) {
        // Streaming mode: never got every field in time. Return whatever real ticks did arrive
        // rather than discarding them — partial bid/ask still beats an all-NaN snapshot, and the
        // caller's own NaN checks (e.g. delta → BS-fallback) decide what's usable.
        pending.snapshot
    } finally {
        registry.pendingMarketData.remove(reqId)
        client.cancelMktData(reqId)
    }
}

internal fun midPrice(
    bid: Double,
    ask: Double,
): BigDecimal {
    val b = bid.takeIf { !it.isNaN() && it > 0 }
    val a = ask.takeIf { !it.isNaN() && it > 0 }
    return when {
        b != null && a != null -> BigDecimal((b + a) / 2).setScale(4, RoundingMode.HALF_UP)
        b != null -> BigDecimal(b).setScale(4, RoundingMode.HALF_UP)
        a != null -> BigDecimal(a).setScale(4, RoundingMode.HALF_UP)
        else -> BigDecimal.ZERO
    }
}
