package cz.solvina.options.domain.features.account

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Service
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** How many times a caller polls the broker position feed before trusting/abandoning a snapshot. */
private const val POSITION_POLLS = 5
private const val POSITION_POLL_DELAY_MS = 500L

/** Max wait for the broker's initial portfolio download before a snapshot decision. */
private val INITIAL_SNAPSHOT_TIMEOUT = 10.seconds

/**
 * A broker position snapshot that any recovery path can safely act on — or `null`, meaning "do not
 * decide anything from this".
 *
 * The distinction is the whole point. An empty list from a feed that has not finished its initial
 * download looks exactly like a flat account, and treating one as the other is how a live position
 * gets administratively closed while its shares are still at the broker (the 2026-07-08 exit
 * false-verify). So: poll a few times for a non-empty answer, and if the feed was never warm and
 * never returned anything, report unavailable rather than empty.
 *
 * Shared by flag and stock recovery so both obey the same rule; neither should re-derive it.
 */
@Service
class BrokerPositionsSnapshotService(
    private val positionsPort: PositionsPort,
) {
    suspend fun fetch(caller: String): List<AccountPosition>? {
        val ready = positionsPort.awaitInitialSnapshot(INITIAL_SNAPSHOT_TIMEOUT)
        var lastSuccessfulSnapshot: List<AccountPosition>? = null
        repeat(POSITION_POLLS) { attempt ->
            val snapshot =
                runCatching { positionsPort.getPositions() }
                    .onFailure { e -> logger.warn { "$caller: position fetch failed (attempt $attempt): ${e.message}" } }
                    .getOrNull()
            if (snapshot != null) {
                lastSuccessfulSnapshot = snapshot
                if (snapshot.isNotEmpty()) return snapshot
            }
            if (attempt < POSITION_POLLS - 1) delay(POSITION_POLL_DELAY_MS.milliseconds)
        }
        return if (!ready && lastSuccessfulSnapshot.isNullOrEmpty()) {
            logger.warn { "$caller: position feed not warmed (initial download pending) — treating as unavailable" }
            null
        } else {
            lastSuccessfulSnapshot
        }
    }
}
