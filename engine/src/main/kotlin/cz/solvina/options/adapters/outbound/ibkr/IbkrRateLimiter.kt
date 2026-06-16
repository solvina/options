package cz.solvina.options.adapters.outbound.ibkr

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.ArrayDeque

private val logger = KotlinLogging.logger {}

/**
 * Tunable limits for [IbkrRateLimiter]. Defaults sit under IBKR's documented ceilings:
 *  - Historical data: ~60 requests / 10 min  → we cap at 55 for headroom.
 *  - Market-data lines: ~100 simultaneous     → we cap at 90.
 *  - Contract details: concurrent requests for the same underlying get paced (~5s); serialize.
 */
@ConfigurationProperties(prefix = "ibkr.rate-limit")
data class IbkrRateLimitConfig(
    val historicalMaxPer10Min: Int = 55,
    val historicalMaxInFlight: Int = 5,
    val historicalMinSpacingMs: Long = 200,
    val pacingBackoffMs: Long = 15_000,
    val contractDetailsMaxInFlight: Int = 1,
    val marketDataLines: Int = 90,
)

/**
 * Central throttle every outbound IBKR request passes through, so pacing is enforced in one place
 * instead of scattered ad-hoc delays. Prevents historical-data pacing violations (error 162),
 * serialises contract-details lookups (which IBKR paces ~5s when issued concurrently for the same
 * underlying), and bounds simultaneous market-data lines under the account cap.
 */
@Component
class IbkrRateLimiter(
    private val config: IbkrRateLimitConfig,
    private val clock: Clock,
) {
    private companion object {
        const val WINDOW_MS = 600_000L // 10 minutes
    }

    // --- Historical data: sliding-window rate limit + in-flight cap + pacing penalty ---
    private val histMutex = Mutex()
    private val histWindow = ArrayDeque<Long>() // request timestamps within the trailing window
    private val histInFlight = Semaphore(config.historicalMaxInFlight)

    @Volatile private var lastHistFireMs = 0L

    @Volatile private var pacingPenaltyUntilMs = 0L

    // --- Contract details: serialise to avoid IBKR's concurrent-request pacing ---
    private val contractDetailsGate = Semaphore(config.contractDetailsMaxInFlight)

    // --- Market-data lines: bounded under the account's simultaneous-line cap ---
    private val marketDataLines = Semaphore(config.marketDataLines)

    /**
     * Acquire permission to fire ONE historical request, suspending as needed to respect the rate
     * window, minimum spacing, any active pacing penalty, and the in-flight cap. The caller MUST
     * call [releaseHistorical] exactly once when the request finishes (end or error).
     */
    suspend fun acquireHistorical() {
        histInFlight.acquire()
        try {
            while (true) {
                val waitMs =
                    histMutex.withLock {
                        val now = clock.millis()
                        while (histWindow.isNotEmpty() && now - histWindow.peekFirst() >= WINDOW_MS) {
                            histWindow.pollFirst()
                        }
                        val penaltyWait = (pacingPenaltyUntilMs - now).coerceAtLeast(0)
                        val spacingWait = (lastHistFireMs + config.historicalMinSpacingMs - now).coerceAtLeast(0)
                        val windowWait =
                            if (histWindow.size >= config.historicalMaxPer10Min) {
                                (histWindow.peekFirst() + WINDOW_MS - now).coerceAtLeast(1)
                            } else {
                                0L
                            }
                        val wait = maxOf(penaltyWait, spacingWait, windowWait)
                        if (wait == 0L) {
                            histWindow.addLast(now)
                            lastHistFireMs = now
                        }
                        wait
                    }
                if (waitMs == 0L) return
                delay(waitMs)
            }
        } catch (e: Throwable) {
            histInFlight.release()
            throw e
        }
    }

    fun releaseHistorical() {
        histInFlight.release()
    }

    /** Called when IBKR reports a historical pacing violation (error 162) so subsequent requests back off. */
    fun notePacingViolation() {
        pacingPenaltyUntilMs = clock.millis() + config.pacingBackoffMs
        logger.warn { "IBKR pacing violation noted — backing off historical requests for ${config.pacingBackoffMs}ms" }
    }

    /** Run [block] (a contract-details lookup) serialised against other contract-details lookups. */
    suspend fun <T> withContractDetails(block: suspend () -> T): T = contractDetailsGate.withPermit { block() }

    /** Acquire one market-data line. Caller MUST call [releaseMarketDataLine] when the subscription ends. */
    suspend fun acquireMarketDataLine() = marketDataLines.acquire()

    fun releaseMarketDataLine() = marketDataLines.release()

    fun availableMarketDataLines(): Int = marketDataLines.availablePermits
}
