package cz.solvina.options.adapters.outbound.ibkr.registry

import cz.solvina.options.domain.features.bars.RealTimeBar
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

internal data class PendingMarketDataRequest(
    val deferred: CompletableDeferred<MarketDataSnapshot>,
    /** Streaming reqMktData (snapshot=false) never emits tickSnapshotEnd, so the deferred is resolved
     *  as soon as this predicate accepts the accumulated snapshot. Defaults to "never ready", which
     *  preserves the old timeout-only behaviour for callers that don't supply one. */
    val isReady: (MarketDataSnapshot) -> Boolean = { false },
    @Volatile var snapshot: MarketDataSnapshot = MarketDataSnapshot(),
)

/** Continuous reqMktData(snapshot=false) subscription. Each tick replaces [snapshot] with a new
 *  immutable instance via copy(); [onUpdate] is called after each replacement. */
internal data class PendingContinuousMarketDataRequest(
    @Volatile var snapshot: MarketDataSnapshot = MarketDataSnapshot(),
    val onUpdate: (MarketDataSnapshot) -> Unit = {},
)

/** A single bid/ask event from reqTickByTick("BidAsk"). */
data class TickByTickBidAsk(
    val time: Long,
    val bidPrice: Double,
    val askPrice: Double,
)

/** Active reqTickByTick subscription. [trySend] is called on every incoming tick. */
internal data class PendingTickByTickRequest(
    val trySend: (TickByTickBidAsk) -> Boolean,
)

/** Active reqRealTimeBars subscription. [onError] receives per-request IBKR errors (e.g. rejection
 *  for missing live subscription) — without it a failed bars subscription is indistinguishable from
 *  a quiet market and the flag strategy goes silently inert. */
internal data class PendingRealTimeBarsRequest(
    val onBar: (RealTimeBar) -> Unit,
    val onError: (Int, String) -> Unit = { _, _ -> },
)

/**
 * With reqMarketDataType(3) (paper without a live subscription) IBKR delivers the same callbacks
 * under delayed tick IDs. Fold them onto the live IDs so the snapshot/stream handling below is
 * mode-agnostic — without this every delayed tick fell through the field filters, snapshots never
 * completed, and quotes degraded to the Black-Scholes synthetic fallback (which exit decisions
 * deliberately refuse to act on).
 */
internal fun normalizeDelayedPriceField(field: Int): Int =
    when (field) {
        66 -> 1 // DELAYED_BID
        67 -> 2 // DELAYED_ASK
        68 -> 4 // DELAYED_LAST
        75 -> 9 // DELAYED_CLOSE
        else -> field
    }

/** Delayed bid/ask/last/model option computation (80–83) → live equivalents (10–13). */
internal fun normalizeDelayedOptionComputationField(field: Int): Int = if (field in 80..83) field - 70 else field

@Component
class IbkrMarketDataRegistry(
    private val idCounter: IbkrIdCounter,
) {
    internal val pendingMarketData = ConcurrentHashMap<Int, PendingMarketDataRequest>()
    internal val pendingContinuousMarketData = ConcurrentHashMap<Int, PendingContinuousMarketDataRequest>()
    internal val pendingTickByTick = ConcurrentHashMap<Int, PendingTickByTickRequest>()

    /** Active reqRealTimeBars subscriptions. */
    internal val pendingRealTimeBars = ConcurrentHashMap<Int, PendingRealTimeBarsRequest>()

    fun onRealtimeBar(
        reqId: Int,
        time: Long,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long,
        wap: Double,
    ) {
        val bar =
            RealTimeBar(
                time = Instant.ofEpochSecond(time),
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume,
                wap = wap,
            )
        pendingRealTimeBars[reqId]?.onBar?.invoke(bar)
    }

    fun nextReqId(): Int = idCounter.next()

    /** Resolve a streaming snapshot request the moment its readiness predicate is satisfied. Without
     *  this the deferred only ever completes on timeout (which discards the real ticks), so every
     *  option quote degrades to a Black-Scholes "synthetic" fallback and no spread is ever launched. */
    private fun completeIfReady(reqId: Int) {
        val request = pendingMarketData[reqId] ?: return
        val snapshot = request.snapshot
        if (request.isReady(snapshot) && pendingMarketData.remove(reqId, request)) {
            request.deferred.complete(snapshot)
        }
    }

    fun onTickPrice(
        reqId: Int,
        field: Int,
        price: Double,
    ) {
        val normalizedField = normalizeDelayedPriceField(field)
        val now = Instant.now()
        pendingMarketData[reqId]?.let { request ->
            request.snapshot =
                when (normalizedField) {
                    1 -> request.snapshot.copy(bid = price, asOf = now)
                    2 -> request.snapshot.copy(ask = price, asOf = now)
                    4 -> request.snapshot.copy(last = price, asOf = now)
                    9 -> request.snapshot.copy(close = price, asOf = now)
                    else -> return@let
                }
            completeIfReady(reqId)
        }
        pendingContinuousMarketData[reqId]?.let { request ->
            val updated =
                when (normalizedField) {
                    1 -> request.snapshot.copy(bid = price, asOf = now)
                    2 -> request.snapshot.copy(ask = price, asOf = now)
                    4 -> request.snapshot.copy(last = price, asOf = now)
                    9 -> request.snapshot.copy(close = price, asOf = now)
                    else -> return@let
                }
            request.snapshot = updated
            request.onUpdate(updated)
        }
    }

    fun onTickOptionComputation(
        reqId: Int,
        field: Int,
        impliedVol: Double,
        delta: Double,
        gamma: Double,
        vega: Double,
        theta: Double,
    ) {
        if (normalizeDelayedOptionComputationField(field) !in 10..13) return
        val sentinel = Double.MAX_VALUE
        val now = Instant.now()

        fun MarketDataSnapshot.withGreeks() =
            copy(
                delta = if (!delta.isNaN() && delta != sentinel) delta else this.delta,
                impliedVol = if (!impliedVol.isNaN() && impliedVol != sentinel) impliedVol else this.impliedVol,
                gamma = if (!gamma.isNaN() && gamma != sentinel) gamma else this.gamma,
                vega = if (!vega.isNaN() && vega != sentinel) vega else this.vega,
                theta = if (!theta.isNaN() && theta != sentinel) theta else this.theta,
                asOf = now,
            )

        pendingMarketData[reqId]?.let { request ->
            request.snapshot = request.snapshot.withGreeks()
            completeIfReady(reqId)
        }
        // Continuous subscription — update Greeks silently (read on next bid/ask tick via emitIfReady)
        pendingContinuousMarketData[reqId]?.let { request ->
            request.snapshot = request.snapshot.withGreeks()
        }
    }

    fun onTickSnapshotEnd(reqId: Int) {
        val request = pendingMarketData.remove(reqId) ?: return
        request.deferred.complete(request.snapshot)
    }

    fun onTickByTickBidAsk(
        reqId: Int,
        tick: TickByTickBidAsk,
    ) {
        val sent = pendingTickByTick[reqId]?.trySend?.invoke(tick)
        if (sent == false) logger.warn { "[$reqId] Bid/ask tick dropped — channel full" }
    }

    fun onError(
        id: Int,
        code: Int,
        msg: String,
    ) {
        // Real-time bars first: the graceful branches below return unconditionally and would swallow
        // a bars rejection (e.g. 354/10195 for missing live subscription), leaving the flag strategy
        // silently bar-less. The subscriber decides how loudly to surface it.
        pendingRealTimeBars[id]?.let { request ->
            request.onError(code, msg)
            return
        }
        // 200 = no security definition / ambiguous — could indicate options not authorized on the account.
        // 354 = no live subscription, 10197 = competing live session.
        // 10168 = delayed market data not enabled/available for this venue (delayed-mode paper hits
        // this on exchanges without delayed permission) — same safe degradation as 354.
        // IBKR does NOT send tickSnapshotEnd in any of these cases, so complete the deferred ourselves.
        // The caller sees an empty snapshot (delta=NaN) and falls back to analytical pricing.
        if (code == 200 || code == 354 || code == 10197 || code == 10168) {
            pendingMarketData.remove(id)?.let { request ->
                // 200 at WARN: repeated 200s on option market data may indicate account permissions issue.
                if (code == 200) {
                    logger.warn { "Market data $id: code 200 (no security definition), falling back to analytical pricing" }
                } else {
                    logger.debug { "Market data $id: code $code, completing with available snapshot" }
                }
                request.deferred.complete(request.snapshot)
            }
            return
        }
        // 10090 = no subscription for BID_ASK on this exchange; subscription-independent ticks still come.
        // 10167 = informational: IBKR is switching to delayed market data.
        // In both cases tick data and tickSnapshotEnd will still arrive — do not touch the deferred.
        if (code == 10090 || code == 10167) return
        val ex = RuntimeException("IBKR error [code=$code]: $msg")
        pendingMarketData.remove(id)?.deferred?.completeExceptionally(ex)
        pendingContinuousMarketData.remove(id)?.let { logger.warn { "Continuous market data $id errored: $msg" } }
        pendingTickByTick.remove(id)?.let { logger.warn { "TickByTick $id errored: $msg" } }
    }

    fun cancelAllPending(cause: Exception) {
        val count = pendingMarketData.size + pendingContinuousMarketData.size + pendingTickByTick.size + pendingRealTimeBars.size
        if (count > 0) logger.warn { "Cancelling $count pending market data requests due to disconnect" }
        pendingMarketData.values.forEach { it.deferred.completeExceptionally(cause) }
        pendingMarketData.clear()
        // Continuous subscriptions: flows will detect completion via their awaitClose blocks
        pendingContinuousMarketData.clear()
        pendingTickByTick.clear()
        // Real-time bar subscriptions will notice disconnect via their awaitClose blocks
        pendingRealTimeBars.clear()
    }
}
