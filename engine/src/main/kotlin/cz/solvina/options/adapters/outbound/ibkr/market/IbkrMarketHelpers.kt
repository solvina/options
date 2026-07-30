package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Contract
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderIdCounter
import cz.solvina.options.adapters.outbound.ibkr.registry.MarketDataSnapshot
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.seconds

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

@Component
class MarketSnapshotHelper(
    private val registry: IbkrMarketDataRegistry,
    private val idCounter: IbkrOrderIdCounter,
    private val client: EClientSocket,
) {
    suspend fun reqMktDataSnapshot(
        symbol: Symbol,
        contract: Contract,
        purpose: String,
        isReady: (MarketDataSnapshot) -> Boolean,
    ): MarketDataSnapshot = reqMktDataSnapshot(symbol, contract, "", purpose, isReady)

    // [symbol] is the owning domain symbol, passed explicitly by the caller. Deriving it from
    // contract.symbol() is unsafe: conId-routed requests (the cached-scanner path) carry only a
    // conId + exchange, so contract.symbol() is null/blank there. The caller always has it.
    // [purpose] is a short caller-supplied tag (e.g. "exit price check") — this helper is the one
    // choke point every snapshot flows through, so it's the only place that can label the
    // subscribe/cancel log line with *why* a request fired, not just that one did.
    suspend fun reqMktDataSnapshot(
        symbol: Symbol,
        contract: Contract,
        genericTickList: String,
        purpose: String,
        isReady: (MarketDataSnapshot) -> Boolean,
    ): MarketDataSnapshot {
        // Even a short-lived snapshot holds a market-data line between reqMktData and cancelMktData.
        // Acquiring here (the one place every snapshot flows through) keeps the account's line cap a
        // true invariant — previously these requests bypassed the budget entirely.
        val reqId = idCounter.nextOrderId()
        val pending = registry.createPendingMarketDataRequest(reqId, symbol, isReady)
        return try {
            // TWS_LIMITS: +1 market-data line for the duration of ONE snapshot. Self-retiring — the
            // finally below always cancelMktData once the snapshot completes, quiesces, or times out.
            // Shortest-lived line in the system (sub-5s typical); every snapshot flows through here.
            client.reqMktData(reqId, contract, genericTickList, false, false, null)
            MarketDataLineTracker.subscribed("[$symbol] $purpose (reqMktData)")
            val snapshot = withTimeout(5.seconds) { pending.await() }
            logger.debug { "Received market data snapshot: $snapshot" }
            snapshot
        } catch (_: TimeoutCancellationException) {
            // Streaming mode: never got every field in time. Return whatever real ticks did arrive
            // rather than discarding them — partial bid/ask still beats an all-NaN snapshot, and the
            // caller's own NaN checks (e.g. delta → BS-fallback) decide what's usable.
            logger.warn { "Market data snapshot for [$symbol] timed out after 5s" }
            pending.snapshot
        } finally {
            registry.remove(reqId)
            client.cancelMktData(reqId)
            MarketDataLineTracker.unsubscribed("[$symbol] $purpose (reqMktData)")
        }
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
