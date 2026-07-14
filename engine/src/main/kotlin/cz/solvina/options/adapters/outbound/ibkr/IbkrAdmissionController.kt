package cz.solvina.options.adapters.outbound.ibkr

import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.features.market.MarketDataPriority
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
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
 *  - Market-data lines: ~100 simultaneous → we cap at 90, partitioned into per-class reserves.
 *  - Historical data: ~60 requests / 10 min → we cap at 55 for headroom.
 *  - Contract details: concurrent requests for the same underlying get paced (~5s); serialize.
 */
@ConfigurationProperties(prefix = "ibkr.admission")
data class IbkrAdmissionConfig(
    val messagesPerSecond: Int = 45,
    val marketDataLines: Int = 90,
    // Per-class line reserves: lines the OTHER classes may never take. A class may hold more than
    // its reserve by drawing on the shared leftover. SCANNER has no reserve — leftover only.
    val exitReserve: Int = 40, // maxOpenSpreads × 2 leg streams
    val flagReserve: Int = 15, // flag real-time-bars universe
    val execReserve: Int = 8, // combo-quote burst during entry/reprice
    val scannerLineConcurrency: Int = 5, // scanner's own cap, even when leftover is larger
    val scannerLineTimeoutMs: Long = 30_000, // scanner skips the strike when no line frees in time
    val scannerTokenFloor: Int = 10, // scanner sends only while the message bucket has this headroom
    val greeksSnapshotTimeoutMs: Long = 5_000, // per-strike snapshot hard ceiling during a scan
    // Scanner snapshot early-out: once a quote has arrived and no further tick lands for this long, the
    // scanner stops waiting (a not-yet-live delta at the open would otherwise stall the full timeout).
    val scannerGreeksQuiescenceMs: Long = 400,
    val starvationAlertMs: Long = 2_000, // high-priority line wait that triggers an operator alert
    val starvationAlertCooldownMs: Long = 900_000, // one starvation alert per class per 15 min
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
 *  - a market-data line budget partitioned by [MarketDataPriority] bounds simultaneous
 *    subscriptions under the account's ~100 cap while guaranteeing FLAG/EXEC/EXIT their reserved
 *    floors — the SCANNER class draws leftover only, bounded and with timeouts;
 *  - historical-data pacing (error 162) and contract-details serialisation as before.
 * Every acquire is measured: per-class waits/timeouts feed the `ibkrAdmission` health component,
 * a high-priority wait past [IbkrAdmissionConfig.starvationAlertMs] pages the operator, and
 * broker-side limit errors ([noteBrokerLimitHit]) are counted — nonzero means a gap here.
 */
@Component
class IbkrAdmissionController(
    private val config: IbkrAdmissionConfig,
    private val clock: Clock,
    private val alertPort: AlertPort,
    // Shared executionCoroutineScope bean: alerts must not block (or die with) the starved caller.
    private val alertScope: CoroutineScope,
) {
    private companion object {
        const val WINDOW_MS = 600_000L // 10 minutes
        const val PACED_WARN_INTERVAL_MS = 60_000L
    }

    init {
        val reserved = config.exitReserve + config.flagReserve + config.execReserve
        logger.info {
            "IBKR admission: ${config.marketDataLines} lines = EXIT ${config.exitReserve} + FLAG ${config.flagReserve} + " +
                "EXEC ${config.execReserve} reserved, ${config.marketDataLines - reserved} leftover " +
                "(scanner ≤ ${config.scannerLineConcurrency}); ${config.messagesPerSecond} msgs/s " +
                "(scanner floor ${config.scannerTokenFloor})"
        }
        require(reserved < config.marketDataLines) {
            "ibkr.admission reserves ($reserved) must leave leftover under market-data-lines (${config.marketDataLines})"
        }
    }

    // --- Message rate: global token bucket, drawn by GuardedEClientSocket for every outbound call ---
    private val messageBucket = MessageTokenBucket(config.messagesPerSecond)
    private val messagesSent = AtomicLong()
    private val messageWaitTotalMs = AtomicLong()
    private val messageWaitMaxMs = AtomicLong()
    private val scannerHeadroomWaits = AtomicLong()

    @Volatile private var lastPacedWarnMs = 0L

    // --- Broker-side limit errors (100 = msg rate, 101 = line cap, 162/420 = historical pacing).
    // Must stay zero: the admission controller exists to make these impossible.
    private val brokerLimitHits = ConcurrentHashMap<Int, AtomicLong>()

    // --- Market-data lines: partitioned budget. All state below is guarded by lineLock (a plain
    // monitor, not a coroutine Mutex, so releaseMarketDataLine stays callable from awaitClose).
    private val lineLock = Object()
    private val held = IntArray(MarketDataPriority.entries.size)
    private val lineWaiters = ArrayDeque<LineWaiter>()
    private val classStats = MarketDataPriority.entries.map { ClassStats() }

    // Seeded one cooldown in the past so the FIRST starvation can alert immediately (0 would read
    // as "alerted at epoch" and suppress it on clocks that start near zero).
    private val lastStarvationAlertMs = LongArray(MarketDataPriority.entries.size) { -config.starvationAlertCooldownMs }

    private class LineWaiter(
        val priority: MarketDataPriority,
        val grant: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private class ClassStats {
        var acquires = 0L
        var timeouts = 0L
        var waitTotalMs = 0L
        var waitMaxMs = 0L
    }

    // --- Historical data: sliding-window rate limit + in-flight cap + pacing penalty ---
    private val histMutex = Mutex()
    private val histWindow = ArrayDeque<Long>() // request timestamps within the trailing window
    private val histInFlight = Semaphore(config.historicalMaxInFlight)

    @Volatile private var lastHistFireMs = 0L

    @Volatile private var pacingPenaltyUntilMs = 0L

    // --- Contract details: serialise to avoid IBKR's concurrent-request pacing ---
    private val contractDetailsGate = Semaphore(config.contractDetailsMaxInFlight)

    // ------------------------------------------------------------------ message rate

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
     * SCANNER paths call this before issuing a request: suspends until the message bucket holds at
     * least the configured floor, so a scan burst always leaves token headroom for orders/exits.
     * The bucket refills at the full rate, so waits here are short (~floor/rate seconds).
     */
    suspend fun awaitScannerMessageHeadroom() {
        if (messageBucket.level() >= config.scannerTokenFloor) return
        scannerHeadroomWaits.incrementAndGet()
        while (messageBucket.level() < config.scannerTokenFloor) delay(25)
    }

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
        if (code == 100 || code == 101) {
            alertScope.launch {
                alertPort.send(
                    AlertLevel.CRITICAL,
                    "IBKR limit hit: error $code",
                    "IBKR reported ${if (code == 100) "max messages/sec exceeded" else "max market-data lines reached"} " +
                        "(occurrence #$count). The admission controller should make this impossible — " +
                        "some request path is bypassing it.",
                )
            }
        }
    }

    fun brokerLimitHitCounts(): Map<Int, Long> = brokerLimitHits.mapValues { it.value.get() }

    // ------------------------------------------------------------------ market-data lines

    private fun reserveOf(p: MarketDataPriority): Int =
        when (p) {
            MarketDataPriority.EXIT -> config.exitReserve
            MarketDataPriority.FLAG -> config.flagReserve
            MarketDataPriority.EXEC -> config.execReserve
            MarketDataPriority.SCANNER -> 0
        }

    /** May [p] take one line now? Granting must leave every OTHER class's unmet reserve intact. */
    private fun canGrantLocked(p: MarketDataPriority): Boolean {
        val available = config.marketDataLines - held.sum()
        if (available < 1) return false
        if (p == MarketDataPriority.SCANNER && held[p.ordinal] >= config.scannerLineConcurrency) return false
        val unmetOthers =
            MarketDataPriority.entries
                .filter { it != p }
                .sumOf { maxOf(0, reserveOf(it) - held[it.ordinal]) }
        return available - 1 >= unmetOthers
    }

    /** Grant queued waiters while capacity allows — reserved (high) classes first, FIFO within. */
    private fun grantWaitersLocked() {
        while (true) {
            val next =
                lineWaiters.firstOrNull { it.priority != MarketDataPriority.SCANNER && canGrantLocked(it.priority) }
                    ?: lineWaiters.firstOrNull { it.priority == MarketDataPriority.SCANNER && canGrantLocked(it.priority) }
                    ?: return
            lineWaiters.remove(next)
            held[next.priority.ordinal]++
            next.grant.complete(Unit)
        }
    }

    /**
     * Acquire one market-data line for [priority], suspending until the partitioned budget allows
     * it. Reserved classes (FLAG/EXEC/EXIT) can always reach at least their reserve; a wait past
     * the starvation threshold alerts the operator. The caller MUST call [releaseMarketDataLine]
     * with the SAME priority when the subscription ends.
     */
    suspend fun acquireMarketDataLine(priority: MarketDataPriority = MarketDataPriority.EXEC) {
        tryAcquireMarketDataLine(priority, timeoutMs = null)
    }

    /** Scanner-flavoured acquire: bounded wait, false = skip this request (a choke signal, not a hang). */
    suspend fun tryAcquireScannerLine(): Boolean = tryAcquireMarketDataLine(MarketDataPriority.SCANNER, config.scannerLineTimeoutMs)

    /**
     * Acquire with an optional bounded wait. Returns false only on timeout ([timeoutMs] non-null).
     * On cancellation the pending (or just-granted) line is cleaned up before rethrowing.
     */
    suspend fun tryAcquireMarketDataLine(
        priority: MarketDataPriority,
        timeoutMs: Long?,
    ): Boolean {
        val startMs = clock.millis()
        val waiter =
            synchronized(lineLock) {
                if (canGrantLocked(priority)) {
                    held[priority.ordinal]++
                    classStats[priority.ordinal].acquires++
                    return true
                }
                LineWaiter(priority).also { lineWaiters += it }
            }
        try {
            val granted =
                if (timeoutMs == null) {
                    waiter.grant.await()
                    true
                } else {
                    withTimeoutOrNull(timeoutMs) { waiter.grant.await() } != null
                }
            val waitedMs = clock.millis() - startMs
            if (!granted) {
                synchronized(lineLock) {
                    if (!waiter.grant.isCompleted) {
                        lineWaiters.remove(waiter)
                        classStats[priority.ordinal].timeouts++
                        return false
                    }
                    // Granted in the timeout race — the line is ours after all.
                }
            }
            recordLineWait(priority, waitedMs)
            return true
        } catch (e: CancellationException) {
            synchronized(lineLock) {
                if (waiter.grant.isCompleted) {
                    // Granted but the caller is gone — hand the line straight back.
                    held[priority.ordinal] = maxOf(0, held[priority.ordinal] - 1)
                    grantWaitersLocked()
                } else {
                    lineWaiters.remove(waiter)
                }
            }
            throw e
        }
    }

    fun releaseMarketDataLine(priority: MarketDataPriority = MarketDataPriority.EXEC) {
        synchronized(lineLock) {
            held[priority.ordinal] = maxOf(0, held[priority.ordinal] - 1)
            grantWaitersLocked()
        }
    }

    private fun recordLineWait(
        priority: MarketDataPriority,
        waitedMs: Long,
    ) {
        val starved =
            synchronized(lineLock) {
                val stats = classStats[priority.ordinal]
                stats.acquires++
                stats.waitTotalMs += waitedMs
                stats.waitMaxMs = maxOf(stats.waitMaxMs, waitedMs)
                if (priority != MarketDataPriority.SCANNER && waitedMs >= config.starvationAlertMs) {
                    val now = clock.millis()
                    (now - lastStarvationAlertMs[priority.ordinal] >= config.starvationAlertCooldownMs).also {
                        if (it) lastStarvationAlertMs[priority.ordinal] = now
                    }
                } else {
                    false
                }
            }
        if (starved) {
            logger.error {
                "IBKR admission STARVATION: $priority waited ${waitedMs}ms for a market-data line " +
                    "(threshold ${config.starvationAlertMs}ms) — reserves are mis-sized or lines are leaking"
            }
            alertScope.launch {
                alertPort.send(
                    AlertLevel.WARN,
                    "IBKR admission: $priority starved",
                    "$priority waited ${waitedMs}ms for a market-data line (alert threshold " +
                        "${config.starvationAlertMs}ms).\nSnapshot: ${describeLines()}\n" +
                        "High-priority classes should never wait — check reserve sizing " +
                        "(ibkr.admission.*) or a line leak.",
                )
            }
        }
    }

    fun availableMarketDataLines(): Int = synchronized(lineLock) { config.marketDataLines - held.sum() }

    /** One-line human summary for alerts/logs. */
    fun describeLines(): String =
        synchronized(lineLock) {
            val perClass =
                MarketDataPriority.entries.joinToString(" ") {
                    "${it.name}=${held[it.ordinal]}/${reserveOf(it).takeIf { r -> r > 0 } ?: "leftover"}"
                }
            "lines ${config.marketDataLines - held.sum()}/${config.marketDataLines} free; held: $perClass; waiting: ${lineWaiters.size}"
        }

    /** Full stats snapshot for the `ibkrAdmission` health component. */
    fun snapshot(): AdmissionSnapshot {
        val classes =
            synchronized(lineLock) {
                MarketDataPriority.entries.associate { p ->
                    val s = classStats[p.ordinal]
                    p.name to
                        LineClassSnapshot(
                            held = held[p.ordinal],
                            reserve = reserveOf(p),
                            acquires = s.acquires,
                            timeouts = s.timeouts,
                            waitTotalMs = s.waitTotalMs,
                            waitMaxMs = s.waitMaxMs,
                        )
                }
            }
        return AdmissionSnapshot(
            linesTotal = config.marketDataLines,
            linesAvailable = availableMarketDataLines(),
            lineClasses = classes,
            messageTokenLevel = messageBucket.level(),
            messagesSent = messagesSent.get(),
            messageWaitTotalMs = messageWaitTotalMs.get(),
            messageWaitMaxMs = messageWaitMaxMs.get(),
            scannerHeadroomWaits = scannerHeadroomWaits.get(),
            brokerLimitHits = brokerLimitHitCounts(),
        )
    }

    // ------------------------------------------------------------------ historical + contract details

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
}

data class LineClassSnapshot(
    val held: Int,
    val reserve: Int,
    val acquires: Long,
    val timeouts: Long,
    val waitTotalMs: Long,
    val waitMaxMs: Long,
)

data class AdmissionSnapshot(
    val linesTotal: Int,
    val linesAvailable: Int,
    val lineClasses: Map<String, LineClassSnapshot>,
    val messageTokenLevel: Double,
    val messagesSent: Long,
    val messageWaitTotalMs: Long,
    val messageWaitMaxMs: Long,
    val scannerHeadroomWaits: Long,
    val brokerLimitHits: Map<Int, Long>,
)
