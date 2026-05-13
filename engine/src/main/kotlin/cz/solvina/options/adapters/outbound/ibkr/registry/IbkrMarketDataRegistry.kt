package cz.solvina.options.adapters.outbound.ibkr.registry

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

internal data class PendingMarketDataRequest(
    val deferred: CompletableDeferred<MarketDataSnapshot>,
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

@Component
class IbkrMarketDataRegistry(
    private val idCounter: IbkrIdCounter,
) {
    internal val pendingMarketData = ConcurrentHashMap<Int, PendingMarketDataRequest>()
    internal val pendingContinuousMarketData = ConcurrentHashMap<Int, PendingContinuousMarketDataRequest>()
    internal val pendingTickByTick = ConcurrentHashMap<Int, PendingTickByTickRequest>()

    fun nextReqId(): Int = idCounter.next()

    fun onTickPrice(
        reqId: Int,
        field: Int,
        price: Double,
    ) {
        pendingMarketData[reqId]?.let { request ->
            request.snapshot =
                when (field) {
                    1 -> request.snapshot.copy(bid = price)
                    2 -> request.snapshot.copy(ask = price)
                    4 -> request.snapshot.copy(last = price)
                    9 -> request.snapshot.copy(close = price)
                    else -> return@let
                }
        }
        pendingContinuousMarketData[reqId]?.let { request ->
            val updated =
                when (field) {
                    1 -> request.snapshot.copy(bid = price)
                    2 -> request.snapshot.copy(ask = price)
                    4 -> request.snapshot.copy(last = price)
                    9 -> request.snapshot.copy(close = price)
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
        if (field !in 10..13) return
        val sentinel = Double.MAX_VALUE

        fun MarketDataSnapshot.withGreeks() =
            copy(
                delta = if (!delta.isNaN() && delta != sentinel) delta else this.delta,
                impliedVol = if (!impliedVol.isNaN() && impliedVol != sentinel) impliedVol else this.impliedVol,
                gamma = if (!gamma.isNaN() && gamma != sentinel) gamma else this.gamma,
                vega = if (!vega.isNaN() && vega != sentinel) vega else this.vega,
                theta = if (!theta.isNaN() && theta != sentinel) theta else this.theta,
            )

        pendingMarketData[reqId]?.let { request ->
            request.snapshot = request.snapshot.withGreeks()
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
        // 354 = no live subscription, 10197 = competing live session.
        // IBKR does NOT send tickSnapshotEnd in either case, so complete the deferred ourselves.
        // The caller sees an empty snapshot (delta=NaN) and can fall back to analytical pricing.
        if (code == 354 || code == 10197) {
            pendingMarketData.remove(id)?.let { request ->
                logger.debug { "Market data $id: code $code, completing with available snapshot" }
                request.deferred.complete(request.snapshot)
            }
            return
        }
        // 10167 = informational: IBKR is switching to delayed market data.
        // Actual tick data (and tickSnapshotEnd) will still arrive — do not touch the deferred.
        if (code == 10167) return
        val ex = RuntimeException("IBKR error [code=$code]: $msg")
        pendingMarketData.remove(id)?.deferred?.completeExceptionally(ex)
        pendingContinuousMarketData.remove(id)?.let { logger.warn { "Continuous market data $id errored: $msg" } }
        pendingTickByTick.remove(id)?.let { logger.warn { "TickByTick $id errored: $msg" } }
    }

    fun cancelAllPending(cause: Exception) {
        val count = pendingMarketData.size + pendingContinuousMarketData.size + pendingTickByTick.size
        if (count > 0) logger.warn { "Cancelling $count pending market data requests due to disconnect" }
        pendingMarketData.values.forEach { it.deferred.completeExceptionally(cause) }
        pendingMarketData.clear()
        // Continuous subscriptions: flows will detect completion via their awaitClose blocks
        pendingContinuousMarketData.clear()
        pendingTickByTick.clear()
    }
}
