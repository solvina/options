package cz.solvina.options.adapters.inbound.lifecycle

import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.AccountTradingPort
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.flag.BracketOrderPort
import cz.solvina.options.domain.features.flag.FlagExecutionService
import cz.solvina.options.domain.features.flag.FlagPort
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}

/** How many times recovery polls the broker position feed before trusting/abandoning a snapshot. */
private const val RECOVERY_POSITION_POLLS = 5
private const val RECOVERY_POSITION_POLL_DELAY_MS = 500L

/** Max wait for the broker's initial portfolio download before a snapshot decision. */
private val INITIAL_SNAPSHOT_TIMEOUT = 10.seconds

/**
 * A protective stop we submitted must show up in the broker's open-order list within this window
 * before recovery is allowed to submit another for the same symbol.
 *
 * Without this, recovery has no memory between runs: it submits a stop, the order never appears in
 * `getOpenOrders()`, the next run therefore still sees an unprotected position and submits another.
 * On 2026-08-05 that produced 32 trailing-stop orders for two positions in 72 minutes, none of them
 * ever acknowledged. Deliberately longer than the recovery interval so a slow acknowledgement can
 * never be mistaken for a lost order.
 */
private val REPROTECT_ACK_GRACE = 10.minutes

/** Consecutive unacknowledged submissions for one symbol before escalating to a CRITICAL alert. */
private const val REPROTECT_ALERT_AFTER = 3

/**
 * Re-attaches the engine to flag positions it stopped watching.
 *
 * Flag order lifecycle is registered in-memory when the bracket order is placed — a restart or an
 * IBKR disconnect drops that local registration while the GTC trailing stop lives on at the broker.
 * The row then stays PENDING/OPEN forever, and a later manual/EOD close would sell shares the broker
 * no longer holds (the 2026-07 short-stock-orphan incident). This service runs at startup and
 * periodically:
 *
 *  - orders still working at the broker → re-register lifecycle tracking;
 *  - entry filled while unwatched (protective child active, or shares held) → adopt as OPEN,
 *    re-placing the protective trailing stop if it is gone;
 *  - exit filled while unwatched (no orders, shares gone) → close as CLOSED_EXTERNAL, never sell;
 *  - entry expired without filling (no orders, no shares) → ENTRY_TIMEOUT.
 *
 * Repeated runs are no-ops for healthy positions already registered in memory.
 */
@Component
class FlagRecoveryService(
    private val flagPort: FlagPort,
    private val bracketOrderPort: BracketOrderPort,
    private val flagExecutionService: FlagExecutionService,
    private val accountTradingPort: AccountTradingPort,
    private val positionsPort: PositionsPort,
    private val alertPort: AlertPort,
    private val connectionStatusPort: ConnectionStatusPort,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    /** Last protective stop this service submitted per symbol, for the acknowledgement guard. */
    private data class ReprotectAttempt(
        val at: Instant,
        val orderId: Int,
        val consecutiveUnacked: Int,
    )

    private val lastReprotect = ConcurrentHashMap<String, ReprotectAttempt>()

    @Scheduled(
        fixedDelayString = "\${flag-recovery.delay-ms:300000}",
        initialDelayString = "\${flag-recovery.initial-delay-ms:180000}",
        scheduler = "criticalTaskScheduler",
    )
    fun scheduledRecover() {
        if (!connectionStatusPort.isConnected()) {
            logger.debug { "Flag recovery skipped: IBKR not connected" }
            return
        }
        scope.launch {
            runCatching { recover() }
                .onFailure { e -> logger.error(e) { "Flag recovery failed: ${e.message}" } }
        }
    }

    suspend fun recover() =
        mutex.withLock {
            val unwatched = flagPort.findByStatuses(FlagStatus.ACTIVE_STATUSES)
            if (unwatched.isEmpty()) return@withLock

            logger.info { "Flag recovery: reconciling ${unwatched.size} PENDING/OPEN/CLOSING row(s)" }
            val openOrderIds =
                runCatching { accountTradingPort.getOpenOrders() }
                    .onFailure { e -> logger.warn(e) { "Flag recovery: cannot fetch open orders — skipping this run" } }
                    .getOrNull()
                    ?.map { it.orderId }
                    ?.toSet() ?: return@withLock

            // First resolve rows whose orders are still working; only the leftovers need the
            // position snapshot. Shares claimed by re-armed rows are deducted so a second row on
            // the same symbol cannot claim them again.
            val noOrders = mutableListOf<FlagPosition>()
            val claimed = mutableMapOf<String, Int>()
            for (row in unwatched) {
                val exitIds = setOf(row.stopLossOrderId, row.profitTargetOrderId)
                when {
                    row.status == FlagStatus.CLOSING && row.closeOrderId in openOrderIds -> {
                        logger.info {
                            "Flag recovery: [${row.symbol}] close order ${row.closeOrderId} still working — registering lifecycle tracking"
                        }
                        flagExecutionService.register(row)
                    }
                    row.status == FlagStatus.PENDING && row.entryOrderId in openOrderIds -> {
                        logger.info {
                            "Flag recovery: [${row.symbol}] entry order ${row.entryOrderId} still working — registering lifecycle tracking"
                        }
                        flagExecutionService.register(row)
                    }
                    exitIds.any { it in openOrderIds } -> {
                        // A protective child only activates once the parent fills — the entry filled.
                        val open =
                            if (row.status == FlagStatus.PENDING) {
                                logger.info {
                                    "Flag recovery: [${row.symbol}] protective order working for a PENDING row — entry filled while unwatched, promoting to OPEN"
                                }
                                flagPort.update(row.copy(status = FlagStatus.OPEN))
                            } else {
                                row
                            }
                        claimed.merge(row.symbol.value, row.shares, Int::plus)
                        logger.info { "Flag recovery: [${row.symbol}] protective order still working — registering lifecycle tracking" }
                        flagExecutionService.register(open)
                    }
                    else -> noOrders += row
                }
            }
            if (noOrders.isEmpty()) return@withLock

            val snapshot = fetchPositionsSnapshot()
            if (snapshot == null) {
                logger.warn {
                    "Flag recovery: ${noOrders.size} row(s) have no working orders but positions are unavailable — leaving untouched for the next run"
                }
                return@withLock
            }
            val availableLong = mutableMapOf<String, Int>()
            for (p in snapshot) if (p.secType == "STK") availableLong.merge(p.symbol, p.quantity.toInt(), Int::plus)
            for ((sym, qty) in claimed) availableLong.merge(sym, -qty, Int::plus)

            val externallyClosed = mutableListOf<String>()
            for (row in noOrders) {
                val available = (availableLong[row.symbol.value] ?: 0).coerceAtLeast(0)
                when {
                    available >= row.shares -> {
                        availableLong.merge(row.symbol.value, -row.shares, Int::plus)
                        reprotect(row, openOrderIds)
                    }
                    row.status == FlagStatus.PENDING -> {
                        logger.info { "Flag recovery: [${row.symbol}] entry order gone and shares not held — entry never filled" }
                        flagPort.update(
                            row.copy(
                                status = FlagStatus.ENTRY_TIMEOUT,
                                closeReason = "recovery_entry_not_filled",
                                closedAt = Instant.now(clock),
                            ),
                        )
                    }
                    else -> {
                        logger.warn {
                            "Flag recovery: [${row.symbol}] protective order gone and shares not held (${row.shares} expected, " +
                                "$available available) — exit filled while unwatched; closing administratively (NOT selling)"
                        }
                        flagPort.update(
                            row.copy(
                                status = FlagStatus.CLOSED_EXTERNAL,
                                closeReason = "recovery_exit_filled_externally",
                                closedAt = Instant.now(clock),
                            ),
                        )
                        externallyClosed += "${row.symbol.value} ×${row.shares}"
                    }
                }
            }
            if (externallyClosed.isNotEmpty()) {
                alertPort.send(
                    AlertLevel.WARN,
                    "Flag positions closed externally: ${externallyClosed.size}",
                    "Exits filled at the broker while the engine was not watching (realized P&L unknown, check broker statements):\n" +
                        externallyClosed.joinToString("\n"),
                )
            }
        }

    /**
     * Shares are at the broker but no protective order is working — re-place the trailing stop.
     * Re-arm at the RATCHETED trigger (highest seen − trail), not the initial stop: the vanished
     * order had trailed server-side, and re-arming from the stale DB stop would silently lower the
     * live trigger back to entry-time protection, giving back locked-in profit on the next dip.
     */
    private suspend fun reprotect(
        row: FlagPosition,
        openOrderIds: Set<Int>,
    ) {
        val symbol = row.symbol.value
        val previous = lastReprotect[symbol]
        if (previous != null) {
            if (previous.orderId in openOrderIds) {
                // The broker took it after all; the position is protected and this row is stale.
                lastReprotect.remove(symbol)
            } else {
                val waited = Duration.between(previous.at, Instant.now(clock))
                if (waited < REPROTECT_ACK_GRACE.toJavaDuration()) {
                    logger.warn {
                        "Flag recovery: [$symbol] stop ${previous.orderId} submitted ${waited.seconds}s ago has still not " +
                            "appeared in open orders — waiting for acknowledgement instead of submitting another"
                    }
                    return
                }
                // Grace elapsed and the order never landed: that submission is lost, not slow.
                if (previous.consecutiveUnacked + 1 >= REPROTECT_ALERT_AFTER) {
                    alertPort.send(
                        AlertLevel.CRITICAL,
                        "Cannot protect flag position: $symbol",
                        "${row.shares} shares of $symbol are held with NO working protective order. " +
                            "${previous.consecutiveUnacked + 1} trailing stops have been submitted and none was ever " +
                            "acknowledged by the broker — automatic re-protection is not working. Place a stop or close " +
                            "the position in TWS.",
                    )
                }
            }
        }

        // Pre-v26 rows have no persisted trail; profitTargetPrice = entryPrice + trailAmount by construction.
        val trailAmount = row.trailAmount ?: row.profitTargetPrice.subtract(row.entryPrice)
        val ratcheted = row.highestPriceSeen?.subtract(trailAmount)
        val initialStop = if (ratcheted != null && ratcheted > row.stopLossPrice) ratcheted else row.stopLossPrice
        val newOrderId =
            runCatching { bracketOrderPort.submitTrailingStopSell(row.symbol, row.shares, initialStop, trailAmount) }
                .getOrElse { e ->
                    logger.error(e) { "Flag recovery: [${row.symbol}] failed to re-place protective trailing stop: ${e.message}" }
                    alertPort.send(
                        AlertLevel.CRITICAL,
                        "Unprotected flag position: ${row.symbol}",
                        "${row.shares} shares of ${row.symbol} are held with NO working protective order and re-placing " +
                            "the trailing stop failed — intervene manually (place a stop or close in TWS).",
                    )
                    return
                }
        lastReprotect[symbol] =
            ReprotectAttempt(
                at = Instant.now(clock),
                orderId = newOrderId,
                consecutiveUnacked = (previous?.consecutiveUnacked ?: 0) + 1,
            )
        val updated =
            flagPort.update(row.copy(status = FlagStatus.OPEN, stopLossOrderId = newOrderId, profitTargetOrderId = newOrderId))
        logger.warn { "Flag recovery: [${row.symbol}] re-protected ${row.shares} shares with a new trailing stop (orderId=$newOrderId)" }
        alertPort.send(
            AlertLevel.WARN,
            "Flag position re-protected: ${row.symbol}",
            "${row.shares} shares of ${row.symbol} were held without a working protective order (it vanished while the " +
                "engine was not watching). A new trailing stop was placed (orderId=$newOrderId) and the position is managed again.",
        )
        flagExecutionService.register(updated)
    }

    /**
     * Broker position snapshot trustworthy enough to base a close/adopt decision on. Null means
     * every broker fetch failed and recovery must not decide anything this run. Empty means at least
     * one successful broker fetch reported a flat account.
     */
    private suspend fun fetchPositionsSnapshot(): List<AccountPosition>? {
        val ready = positionsPort.awaitInitialSnapshot(INITIAL_SNAPSHOT_TIMEOUT)
        var lastSuccessfulSnapshot: List<AccountPosition>? = null
        repeat(RECOVERY_POSITION_POLLS) { attempt ->
            val snapshot =
                runCatching { positionsPort.getPositions() }
                    .onFailure { e -> logger.warn { "Flag recovery: position fetch failed (attempt $attempt): ${e.message}" } }
                    .getOrNull()
            if (snapshot != null) {
                lastSuccessfulSnapshot = snapshot
                if (snapshot.isNotEmpty()) return snapshot
            }
            if (attempt < RECOVERY_POSITION_POLLS - 1) delay(RECOVERY_POSITION_POLL_DELAY_MS.milliseconds)
        }
        // A cold feed (download not yet complete) reporting empty is not a flat account — treat it as
        // unavailable so a live position is never false-closed.
        return if (!ready && lastSuccessfulSnapshot.isNullOrEmpty()) {
            logger.warn { "Flag recovery: position feed not warmed (initial download pending) — treating as unavailable" }
            null
        } else {
            lastSuccessfulSnapshot
        }
    }
}
