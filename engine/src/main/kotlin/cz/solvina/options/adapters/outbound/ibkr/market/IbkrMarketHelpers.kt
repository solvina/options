package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Contract
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.MarketDataSnapshot
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingMarketDataRequest
import cz.solvina.options.domain.features.market.MarketDataPriority
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

private val logger = KotlinLogging.logger {}

// Reserved-class (EXIT/EXEC/FLAG) line-acquire ceiling. Under healthy load the pool grants a line
// instantly, so this only bites when the pool is drained — turning what used to be an unbounded
// wait (a permanent exit-monitor wedge, restart-only) into a skipped cycle. 2026-07-21.
private const val RESERVED_LINE_ACQUIRE_TIMEOUT_MS = 10_000L

internal suspend fun reqMktDataSnapshot(
    registry: IbkrMarketDataRegistry,
    client: EClientSocket,
    contract: Contract,
    genericTickList: String,
    isReady: (MarketDataSnapshot) -> Boolean,
    timeoutMs: Long = 5_000L,
    quiescenceMs: Long = 0L,
): MarketDataSnapshot {
    // Even a short-lived snapshot holds a market-data line between reqMktData and cancelMktData.
    // Acquiring here (the one place every snapshot flows through) keeps the account's line cap a
    // true invariant — previously these requests bypassed the budget entirely. The caller's
    // MarketDataPriority decides which partition pays: SCANNER waits bounded (skip beats hang) and
    // first yields message-bucket headroom to orders/exits; reserved classes acquire directly.
    val priority = coroutineContext[MarketDataPriority] ?: MarketDataPriority.EXEC

    val reqId = registry.nextReqId()
    val deferred = CompletableDeferred<MarketDataSnapshot>()
    val pending = PendingMarketDataRequest(deferred, isReady)
    registry.pendingMarketData[reqId] = pending
    return try {
        // TWS_LIMITS: +1 market-data line for the duration of ONE snapshot. Self-retiring — the
        // finally below always cancelMktData once the snapshot completes, quiesces, or times out.
        // Shortest-lived line in the system (sub-5s typical); every snapshot flows through here.
        client.reqMktData(reqId, contract, genericTickList, false, false, null)
        withTimeout(timeoutMs) { awaitSnapshot(deferred, pending, priority, quiescenceMs) }
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

/**
 * Await a streaming snapshot's completion. Reserved-class callers (exits/entries) wait patiently for
 * [deferred] — the readiness predicate — because giving up on a still-arriving quote is exactly what
 * produced BLIND exit cycles (2026-07-09). A SCANNER caller with a positive [quiescenceMs] instead
 * bails as soon as the stream falls silent: once at least one tick has landed (asOf advanced past its
 * initial value) and none has arrived for [quiescenceMs], the venue has sent what it has, so return
 * the partial snapshot rather than stalling to the hard timeout.
 *
 * This is purely about tick flow — it knows nothing about greeks or the session open. At the open a
 * quote arrives in ~100ms but the option-computation (delta) tick lags; the predicate never trips, so
 * today the request rides the full [reqMktDataSnapshot] timeout. Here it returns ~[quiescenceMs] after
 * the quote goes quiet; the NaN-greeks partial simply makes the scanner skip the strike this cycle and
 * re-evaluate it next scan. Runs inside the caller's withTimeout, which remains the hard ceiling.
 */
internal suspend fun awaitSnapshot(
    deferred: CompletableDeferred<MarketDataSnapshot>,
    pending: PendingMarketDataRequest,
    priority: MarketDataPriority,
    quiescenceMs: Long,
): MarketDataSnapshot {
    if (priority != MarketDataPriority.SCANNER || quiescenceMs <= 0) return deferred.await()
    val startAsOf = pending.snapshot.asOf
    while (true) {
        val before = pending.snapshot.asOf
        withTimeoutOrNull(quiescenceMs) { deferred.await() }?.let { return it }
        val after = pending.snapshot.asOf
        // A tick has landed (asOf moved past the start) and none arrived during the last window →
        // the stream is quiescent; hand back the partial instead of waiting out the hard timeout.
        if (after != startAsOf && after == before) return pending.snapshot
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
