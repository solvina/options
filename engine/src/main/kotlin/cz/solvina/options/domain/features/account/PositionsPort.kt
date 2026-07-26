package cz.solvina.options.domain.features.account

import kotlin.time.Duration

interface PositionsPort {
    suspend fun getPositions(): List<AccountPosition>

    /**
     * Suspends until the broker's initial portfolio download completes (positions are trustworthy),
     * or [timeout] elapses. Returns true if ready. Non-broker feeds are always ready. An empty
     * position list is only "flat account" once this returns true — before it, the feed is cold.
     */
    suspend fun awaitInitialSnapshot(timeout: Duration): Boolean = true
}
