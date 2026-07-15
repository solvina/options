package cz.solvina.options.domain.features.market

import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Always-current market-data flow signal, distinct from the IBKR socket-connection status. The
 * socket can be "connected" while data is fully starved (e.g. a competing session denying quotes),
 * so this tracks whether the engine is actually *receiving* prices: every underlying-price fetch
 * records a success or failure, and any live streaming tick/bar refreshes the freshness heartbeat
 * via [recordLiveTick] (so the signal stays live *between* scans, not just while one is running).
 * [snapshot] reports whether data flowed recently. Read by the `/health/market-data` endpoint and
 * shown as a separate UI light.
 */
@Component
class MarketDataHealthTracker(
    private val clock: Clock,
) {
    data class Snapshot(
        /** A price fetch succeeded or a live tick/bar arrived within [FRESH_MS] — data is live. */
        val flowing: Boolean,
        /** Seconds since the last successful fetch or live tick/bar, or null if none ever. */
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
    private val competingTimes = ArrayDeque<Long>()

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

    /**
     * Live-tick heartbeat, called from the EWrapper on every valid streaming price/bar. Keeps the
     * flow signal fresh *between* scans — [recordSuccess] only fires while a scan is actively
     * fetching, so without this the signal decays to `flowing=false` in the 15-min gap between runs.
     *
     * Deliberately lock-free and does NOT touch the success/failure counters: ticks arrive many per
     * second, so counting them would swamp [Snapshot.successes]. It only advances the freshness
     * timestamp behind [Snapshot.flowing] and [Snapshot.lastSuccessAgeSeconds]. A plain volatile
     * write is enough — concurrent writes with [recordSuccess] just race to "most recent", which is
     * exactly the value we want.
     */
    fun recordLiveTick() {
        lastSuccessAt = clock.millis()
    }

    /** Called when IBKR reports the "connected from a different IP address" / competing-session error. */
    fun recordCompetingSession() {
        val now = clock.millis()
        lastCompetingSessionAt = now
        synchronized(lock) {
            competingTimes.addLast(now)
            while (competingTimes.isNotEmpty() && now - competingTimes.first() > COMPETING_COUNT_WINDOW_MS) competingTimes.removeFirst()
        }
    }

    /** Count of competing-session denials in the trailing hour — for the "N in last hour" alert body. */
    fun competingCountLastHour(): Int =
        synchronized(lock) {
            val now = clock.millis()
            while (competingTimes.isNotEmpty() && now - competingTimes.first() > COMPETING_COUNT_WINDOW_MS) competingTimes.removeFirst()
            competingTimes.size
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

        /** Rolling window for the competing-session denial count reported in alerts. */
        const val COMPETING_COUNT_WINDOW_MS = 3_600_000L // 1 hour
    }
}
