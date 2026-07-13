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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Tunable limits for [IbkrAdmissionController]. Defaults sit under IBKR's documented ceilings:
 *  - Outbound messages: ~50/sec (counts EVERYTHING — orders, cancels, all data requests) → 45.
 *  - Market-data lines: ~100 simultaneous → we cap at 90.
 *  - Historical data: ~60 requests / 10 min → we cap at 55 for headroom.
 *  - Contract details: concurrent requests for the same underlying get paced (~5s); serialize.
 */
@ConfigurationProperties(prefix = "ibkr.admission")
data class IbkrAdmissionConfig(
    val messagesPerSecond: Int = 45,
    val marketDataLines: Int = 90,
    val historicalMaxPer10Min: Int = 55,
    val historicalMaxInFlight: Int = 5,
    val historicalMinSpacingMs: Long = 200,
    val pacingBackoffMs: Long = 15_000,
    val contractDetailsMaxInFlight: Int = 1,
)

/**
 * Central admission control every outbound IBKR request passes through, so ALL broker ceilings are
 * enforced in one place instead of scattered ad-hoc delays:
 *  - a global message-rate token bucket ([paceMessage], wired into [GuardedEClientSocket] so no
 *    call site can bypass it) keeps the ~50 msgs/sec API limit untrippable;
 *  - a market-data line budget bounds simultaneous subscriptions under the account's ~100 cap;
 *  - historical-data pacing (error 162) and contract-details serialisation as before.
 * Broker-side limit errors are counted via [noteBrokerLimitHit] — nonzero means a gap in this
 * controller, so they are loud.
 */
@Component
class IbkrAdmissionController(
    private val config: IbkrAdmissionConfig,
    private val clock: Clock,
) {
    private companion object {
        const val WINDOW_MS = 600_000L // 10 minutes
        const val PACED_WARN_INTERVAL_MS = 60_000L
    }

    // --- Message rate: global token bucket, drawn by GuardedEClientSocket for every outbound call ---
    private val messageBucket = MessageTokenBucket(config.messagesPerSecond)
    private val messagesSent = AtomicLong()
    private val messageWaitTotalMs = AtomicLong()
    private val messageWaitMaxMs = AtomicLong()

    @Volatile private var lastPacedWarnMs = 0L

    // --- Broker-side limit errors (100 = msg rate, 101 = line cap, 162/420 = historical pacing).
    // Must stay zero: the admission controller exists to make these impossible.
    private val brokerLimitHits = ConcurrentHashMap<Int, AtomicLong>()

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
     * BLOCKING: take one message token before an outbound call reaches the socket. Called by
     * [GuardedEClientSocket] only — never hold the client monitor while in here. Waits are ~22ms
     * at saturation; a sustained wait means the engine is producing messages faster than IBKR
     * accepts them, which is worth a (rate-limited) warning even before anything breaks.
     */
    fun paceMessage() {
        val waitedMs = messageBucket.take()
        messagesSent.incrementAndGet()
        if (waitedMs > 0) {
            messageWaitTotalMs.addAndGet(waitedMs)
            messageWaitMaxMs.accumulateAndGet(waitedMs, ::maxOf)
            val now = clock.millis()
            if (waitedMs >= 500 && now - lastPacedWarnMs >= PACED_WARN_INTERVAL_MS) {
                lastPacedWarnMs = now
                logger.warn {
                    "IBKR outbound message paced ${waitedMs}ms — message rate saturated " +
                        "(cap=${config.messagesPerSecond}/s, sent=${messagesSent.get()})"
                }
            }
        }
    }

    /** Fractional message-token level; the scanner's headroom floor reads this. */
    fun messageTokenLevel(): Double = messageBucket.level()

    /**
     * A broker-side limit error arrived (100 msg-rate / 101 line-cap / 162, 420 historical
     * pacing). The whole point of this controller is that these never fire — count them loudly.
     */
    fun noteBrokerLimitHit(code: Int) {
        val count = brokerLimitHits.computeIfAbsent(code) { AtomicLong() }.incrementAndGet()
        logger.error {
            "IBKR LIMIT HIT code=$code (count=$count) — admission control failed to prevent a " +
                "broker-side pacing/limit violation; investigate which path bypassed it"
        }
    }

    fun brokerLimitHitCounts(): Map<Int, Long> = brokerLimitHits.mapValues { it.value.get() }

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

    /** Called when IBKR reports a historical pacing violation (error 162/420) so subsequent requests back off. */
    fun notePacingViolation(code: Int) {
        noteBrokerLimitHit(code)
        pacingPenaltyUntilMs = clock.millis() + config.pacingBackoffMs
        logger.warn { "IBKR pacing violation noted — backing off historical requests for ${config.pacingBackoffMs}ms" }
    }

    /** Run [block] (a contract-details lookup) serialised against other contract-details lookups. */
    suspend fun <T> withContractDetails(block: suspend () -> T): T = contractDetailsGate.withPermit { block() }

    /** Acquire one market-data line. Caller MUST call [releaseMarketDataLine] when the subscription ends. */
    suspend fun acquireMarketDataLine() {
        // DIAGNOSTIC: when no permits remain, acquire() blocks until a line frees — which can delay
        // a spread's tick subscription past calculateFreshCredit's 3s wait, surfacing as a no-tick
        // abort. WARN so market-data-line exhaustion (the line-budget question) is visible.
        if (marketDataLines.availablePermits == 0) {
            logger.warn { "Market-data lines exhausted (cap=${config.marketDataLines}) — acquire will block until a line frees" }
        }
        marketDataLines.acquire()
    }

    fun releaseMarketDataLine() = marketDataLines.release()

    fun availableMarketDataLines(): Int = marketDataLines.availablePermits
}
