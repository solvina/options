package cz.solvina.options.adapters.outbound.ibkr.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private val logger = KotlinLogging.logger {}

/**
 * Resolve an option contract's conId, tolerating a slow/failed network lookup without ever timing
 * out the cache's own in-flight fetch mid-flight.
 *
 * The 6s timeout here is deliberately longer than [IbkrContractCache]'s own 5s internal network
 * timeout, so the cache governs the lookup and cleans up its own in-flight state on failure. A
 * sub-second timeout at the call site cancels the cache mid-lookup and orphans its in-flight
 * deferred — piggybacking callers would then await a value that never arrives (bug class E3).
 *
 * Falls back to a cached (possibly stale) conId if the fresh fetch doesn't land in time, and
 * returns null only when neither is available — callers must decide how to proceed without a conId.
 *
 * Cancellation of the CALLING coroutine is rethrown, never converted into a fallback value — a
 * cancelled execution/close path must not proceed to submit an order.
 */
suspend fun IbkrContractCache.resolveConIdOrCached(key: OptionContractKey): Int? =
    try {
        withTimeout(6_000L) { getOrFetchOptionConId(key) }
    } catch (e: TimeoutCancellationException) {
        cachedFallback(key, e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        cachedFallback(key, e)
    }

private fun IbkrContractCache.cachedFallback(
    key: OptionContractKey,
    cause: Exception,
): Int? {
    val cached = getCachedOptionConId(key)
    if (cached == null) {
        logger.warn(cause) { "[$key] conId lookup failed and no cached value available" }
    }
    return cached
}
