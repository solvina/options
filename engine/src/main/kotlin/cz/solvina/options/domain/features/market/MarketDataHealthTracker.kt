package cz.solvina.options.domain.features.market

import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Always-current market-data flow signal, distinct from the IBKR socket-connection status. The
 * socket can be "connected" while data is fully starved (e.g. a competing session denying quotes),
 * so this tracks whether the engine is actually *receiving* prices: every underlying-price fetch
 * records a success or failure, and [snapshot] reports whether data flowed recently. Read by the
 * `/health/market-data` endpoint and shown as a separate UI light.
 */
@Component
class MarketDataHealthTracker(
    private val clock: Clock,
) {
    data class Snapshot(
        /** A price fetch succeeded within [FRESH_MS] — data is live. */
        val flowing: Boolean,
        /** Seconds since the last successful fetch, or null if none ever. */
        val lastSuccessAgeSeconds: Long?,
        /** Successful fetches in the trailing [WINDOW_MS]. */
        val successes: Int,
        /** Failed fetches in the trailing [WINDOW_MS]. */
        val failures: Int,
        /** Most recent failure reason (for the tooltip), or null when healthy. */
        val lastError: String?,
        /**
         * IBKR reported a competing session (a TWS/app on the same account from another IP) within
         * [COMPETING_FRESH_MS] — the actionable "log out of TWS" cause behind a data outage.
         */
        val competingSession: Boolean,
    )

    private val lock = Any()
    private val successTimes = ArrayDeque<Long>()
    private val failureTimes = ArrayDeque<Long>()

    @Volatile private var lastSuccessAt: Long? = null

    @Volatile private var lastError: String? = null

    @Volatile private var lastCompetingSessionAt: Long? = null

    fun recordSuccess() =
        synchronized(lock) {
            val now = clock.millis()
            successTimes.addLast(now)
            lastSuccessAt = now
            prune(now)
        }

    fun recordFailure(reason: String?) =
        synchronized(lock) {
            val now = clock.millis()
            failureTimes.addLast(now)
            lastError = reason
            prune(now)
        }

    /** Called when IBKR reports the "connected from a different IP address" / competing-session error. */
    fun recordCompetingSession() {
        lastCompetingSessionAt = clock.millis()
    }

    fun snapshot(): Snapshot =
        synchronized(lock) {
            val now = clock.millis()
            prune(now)
            val age = lastSuccessAt?.let { (now - it) / 1000 }
            val flowing = lastSuccessAt?.let { now - it <= FRESH_MS } ?: false
            val competing = lastCompetingSessionAt?.let { now - it <= COMPETING_FRESH_MS } ?: false
            Snapshot(
                flowing = flowing,
                lastSuccessAgeSeconds = age,
                successes = successTimes.size,
                failures = failureTimes.size,
                lastError = if (flowing && failureTimes.isEmpty()) null else lastError,
                competingSession = competing,
            )
        }

    private fun prune(now: Long) {
        while (successTimes.isNotEmpty() && now - successTimes.first() > WINDOW_MS) successTimes.removeFirst()
        while (failureTimes.isNotEmpty() && now - failureTimes.first() > WINDOW_MS) failureTimes.removeFirst()
    }

    companion object {
        /** Rolling window for success/failure counts. */
        const val WINDOW_MS = 600_000L // 10 min

        /** A success newer than this ⇒ data is flowing. */
        const val FRESH_MS = 180_000L // 3 min

        /** A competing-session error newer than this ⇒ still competing (these errors fire every ~10-20s). */
        const val COMPETING_FRESH_MS = 120_000L // 2 min
    }
}
