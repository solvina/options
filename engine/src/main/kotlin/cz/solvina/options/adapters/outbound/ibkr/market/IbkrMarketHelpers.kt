package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Contract
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionController
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.MarketDataSnapshot
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingMarketDataRequest
import cz.solvina.options.domain.features.market.MarketDataPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.coroutines.coroutineContext

/** Readiness predicates for streaming [reqMktDataSnapshot] requests. A streaming subscription
 *  (snapshot=false) never emits tickSnapshotEnd, so the request must declare which fields make its
 *  snapshot "complete" — the registry resolves the deferred as soon as they have all arrived. */
internal object SnapshotReady {
    // IBKR's first tick after a subscribe is often a -1 placeholder ("no cached quote yet") with the
    // real value following moments later. A placeholder must NOT complete the snapshot — completing
    // cancels the subscription and discards the real quote, which read as a BLIND exit cycle for
    // every leg whose placeholder won the race (2026-07-09). NaN > 0 is false, so > 0 covers both.

    /** Underlying price: live last, else previous close. */
    val STOCK_PRICE: (MarketDataSnapshot) -> Boolean = { it.last > 0 || it.close > 0 }

    /** Option quote used for strike selection — needs both sides plus a live delta (greeks). */
    val OPTION_QUOTE: (MarketDataSnapshot) -> Boolean = { it.bid > 0 && it.ask > 0 && !it.delta.isNaN() }

    /** Option price only (mid for exits/repricing) — bid/ask are enough, greeks not required. */
    val OPTION_PRICE: (MarketDataSnapshot) -> Boolean = { it.bid > 0 && it.ask > 0 }
}

/** A SCANNER request found no free market-data line within its bounded wait — skip, don't hang. */
internal class MarketDataLineTimeoutException(
    message: String,
) : RuntimeException(message)

internal suspend fun reqMktDataSnapshot(
    registry: IbkrMarketDataRegistry,
    client: EClientSocket,
    admission: IbkrAdmissionController,
    contract: Contract,
    genericTickList: String,
    isReady: (MarketDataSnapshot) -> Boolean,
    timeoutMs: Long = 5_000L,
): MarketDataSnapshot {
    // Even a short-lived snapshot holds a market-data line between reqMktData and cancelMktData.
    // Acquiring here (the one place every snapshot flows through) keeps the account's line cap a
    // true invariant — previously these requests bypassed the budget entirely. The caller's
    // MarketDataPriority decides which partition pays: SCANNER waits bounded (skip beats hang) and
    // first yields message-bucket headroom to orders/exits; reserved classes acquire directly.
    val priority = coroutineContext[MarketDataPriority] ?: MarketDataPriority.EXEC
    if (priority == MarketDataPriority.SCANNER) {
        admission.awaitScannerMessageHeadroom()
        if (!admission.tryAcquireScannerLine()) {
            throw MarketDataLineTimeoutException(
                "no SCANNER market-data line freed in time for ${contract.symbol()} — " +
                    "scanner is being throttled by open-position load (see health component ibkrAdmission)",
            )
        }
    } else {
        admission.acquireMarketDataLine(priority)
    }
    try {
        val reqId = registry.nextReqId()
        val deferred = CompletableDeferred<MarketDataSnapshot>()
        val pending = PendingMarketDataRequest(deferred, isReady)
        registry.pendingMarketData[reqId] = pending
        return try {
            client.reqMktData(reqId, contract, genericTickList, false, false, null)
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            // Streaming mode: never got every field in time. Return whatever real ticks did arrive
            // rather than discarding them — partial bid/ask still beats an all-NaN snapshot, and the
            // caller's own NaN checks (e.g. delta → BS-fallback) decide what's usable.
            pending.snapshot
        } finally {
            registry.pendingMarketData.remove(reqId)
            client.cancelMktData(reqId)
        }
    } finally {
        admission.releaseMarketDataLine(priority)
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
