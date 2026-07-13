package cz.solvina.options.adapters.outbound.ibkr

/**
 * Blocking token bucket pacing outbound IBKR messages under the ~50 msgs/sec API ceiling.
 * Refills [ratePerSecond] tokens/sec with capacity of one second's refill (bursts up to a full
 * second are fine — IBKR's limit is per-second). [take] is BLOCKING, not suspending, because it
 * runs inside [GuardedEClientSocket]'s synchronous send methods; at saturation a wait is
 * ~1/rate (~22ms at 45/s), and the caller must never hold the client monitor while waiting.
 */
internal class MessageTokenBucket(
    private val ratePerSecond: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Object()
    private var tokens: Double = ratePerSecond.toDouble()
    private var lastRefillNanos: Long = nanoTime()

    /** Take one token, blocking until available. Returns the time waited in ms. */
    fun take(): Long {
        val start = nanoTime()
        synchronized(lock) {
            while (true) {
                refillLocked()
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    return (nanoTime() - start) / 1_000_000
                }
                // Sleep out the deficit; purely time-based, so no notify needed — wait() just
                // times out and re-checks (and re-computes if a concurrent taker won the race).
                val deficitNanos = ((1.0 - tokens) / ratePerSecond * 1e9).toLong()
                lock.wait(maxOf(1L, deficitNanos / 1_000_000))
            }
        }
    }

    /** Current (fractional) token level — the scanner headroom floor reads this. */
    fun level(): Double =
        synchronized(lock) {
            refillLocked()
            tokens
        }

    private fun refillLocked() {
        val now = nanoTime()
        val elapsed = now - lastRefillNanos
        if (elapsed <= 0) return
        tokens = minOf(ratePerSecond.toDouble(), tokens + elapsed / 1e9 * ratePerSecond)
        lastRefillNanos = now
    }
}
