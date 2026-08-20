package cz.solvina.options.adapters.inbound.lifecycle

import cz.solvina.options.domain.features.account.AccountOpenOrder
import cz.solvina.options.domain.features.account.AccountTradingPort
import cz.solvina.options.domain.features.account.BrokerPositionsSnapshotService
import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.strategy.live.StockOrderPort
import cz.solvina.options.domain.features.strategy.live.StockPosition
import cz.solvina.options.domain.features.strategy.live.StockPositionLifecycleService
import cz.solvina.options.domain.features.strategy.live.StockPositionPort
import cz.solvina.options.domain.features.strategy.live.StockPositionStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}

/**
 * A re-placed protective stop must appear in the broker's open orders within this window before
 * another may be submitted for the same symbol.
 *
 * The flag strategy learned this the expensive way on 2026-08-05: without the guard, a submission
 * that is never acknowledged is re-submitted every recovery pass — 32 stops for two positions in 72
 * minutes. Longer than the recovery interval, so a slow acknowledgement is never read as a lost one.
 */
private val REPROTECT_ACK_GRACE = 10.minutes

/** Consecutive unacknowledged submissions for one symbol before escalating to a CRITICAL alert. */
private const val REPROTECT_ALERT_AFTER = 3

/**
 * Hard ceiling on consecutive unacknowledged submissions. Past this the engine stops submitting and
 * leaves it to the human the alert already summoned.
 *
 * AEP, 2026-08-20: an IBKR disconnect (error 10328 on every in-flight order) meant `getOpenOrders()`
 * returned nothing, so a stop that was working the whole time looked absent. 55 stops went out
 * overnight, one per pass, because nothing ever stopped the loop. Re-submitting forever cannot fix a
 * broker that is not answering, and each attempt burns an order id.
 */
private const val REPROTECT_MAX_ATTEMPTS = 5

/**
 * Re-attaches the engine to stock-strategy positions it stopped watching.
 *
 * [StockPositionLifecycleService] tracks order ids in memory, so a restart or an IBKR disconnect
 * drops the registration while the GTC protective orders live on at the broker. Without this, a row
 * stays PENDING/OPEN forever — and because the runner refuses a second entry while one is live, that
 * symbol never trades again.
 *
 * The decision table mirrors [FlagRecoveryService], deliberately: the two strategies place different
 * orders but the failure modes are identical, and the flag version's rules are the ones that have
 * been through real incidents.
 *
 *  - order still working → re-register lifecycle tracking;
 *  - protective order working on a PENDING row → the entry filled while unwatched, adopt as OPEN;
 *  - no orders but shares held → re-place the protective stop (ack-guarded);
 *  - no orders, no shares, PENDING → the day limit expired, ENTRY_UNFILLED;
 *  - no orders, no shares, OPEN → the exit filled while unwatched, CLOSED_EXTERNAL. Never sells.
 *
 * Nothing is decided from an unavailable position feed — see [BrokerPositionsSnapshotService].
 */
@Component
class StockRecoveryService(
    private val positions: StockPositionPort,
    private val orders: StockOrderPort,
    private val lifecycle: StockPositionLifecycleService,
    private val accountTradingPort: AccountTradingPort,
    private val positionsSnapshot: BrokerPositionsSnapshotService,
    private val alertPort: AlertPort,
    private val connectionStatusPort: ConnectionStatusPort,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    private data class ReprotectAttempt(
        val at: Instant,
        val orderId: Int,
        val consecutiveUnacked: Int,
    )

    private val lastReprotect = ConcurrentHashMap<String, ReprotectAttempt>()

    /**
     * Symbols already escalated to CRITICAL. One alert per run of failures, not one per pass — AEP
     * re-alerted for hours. Cleared the moment the symbol is protected again.
     */
    private val alerted = ConcurrentHashMap.newKeySet<String>()

    @Scheduled(
        fixedDelayString = "\${stock-strategies.recovery.delay-ms:300000}",
        initialDelayString = "\${stock-strategies.recovery.initial-delay-ms:180000}",
        scheduler = "criticalTaskScheduler",
    )
    fun scheduledRecover() {
        if (!connectionStatusPort.isConnected()) {
            logger.debug { "Stock recovery skipped: IBKR not connected" }
            return
        }
        scope.launch {
            runCatching { recover() }
                .onFailure { e -> logger.error(e) { "Stock recovery failed: ${e.message}" } }
        }
    }

    suspend fun recover() =
        mutex.withLock {
            val live = positions.findLive()
            if (live.isEmpty()) return@withLock

            logger.info { "Stock recovery: reconciling ${live.size} PENDING/OPEN row(s)" }
            val openOrders =
                runCatching { accountTradingPort.getOpenOrders() }
                    .onFailure { e -> logger.warn(e) { "Stock recovery: cannot fetch open orders — skipping this run" } }
                    .getOrNull() ?: return@withLock
            val openOrderIds = openOrders.map { it.orderId }.toSet()

            // Rows with working orders resolve without the position snapshot. Shares they account for
            // are claimed, so a second row on the same symbol cannot claim them again.
            val noOrders = mutableListOf<StockPosition>()
            val claimed = mutableMapOf<String, Int>()
            for (row in live) {
                val protectiveIds = listOfNotNull(row.stopOrderId, row.targetOrderId)
                when {
                    row.status == StockPositionStatus.PENDING && row.entryOrderId in openOrderIds -> {
                        logger.info {
                            "Stock recovery: [${row.symbol.value}] entry order ${row.entryOrderId} still working — re-registering"
                        }
                        lifecycle.register(row)
                    }
                    protectiveIds.any { it in openOrderIds } -> {
                        // A protective child only activates once its parent fills — so the entry filled.
                        val repaired = repairStopOrderId(row, openOrders, openOrderIds)
                        val open =
                            if (repaired.status == StockPositionStatus.PENDING) {
                                logger.info {
                                    "Stock recovery: [${row.symbol.value}] protective order working for a PENDING row — " +
                                        "entry filled while unwatched, promoting to OPEN"
                                }
                                // The fill price is genuinely unknown here; leaving actualEntryPrice
                                // null keeps it out of the slippage average rather than inventing a
                                // number, and openedAt records when we noticed, not when it filled.
                                positions.save(
                                    repaired.copy(
                                        status = StockPositionStatus.OPEN,
                                        openedAt = repaired.openedAt ?: Instant.now(clock),
                                    ),
                                )
                            } else {
                                repaired
                            }
                        lastReprotect.remove(row.symbol.value)
                        alerted.remove(row.symbol.value)
                        claimed.merge(row.symbol.value, row.shares, Int::plus)
                        lifecycle.register(open)
                    }
                    else -> noOrders += row
                }
            }
            if (noOrders.isEmpty()) return@withLock

            val snapshot = positionsSnapshot.fetch("Stock recovery")
            if (snapshot == null) {
                logger.warn {
                    "Stock recovery: ${noOrders.size} row(s) have no working orders but positions are unavailable — " +
                        "leaving untouched for the next run"
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
                        reprotect(row, openOrders, openOrderIds)
                    }
                    row.status == StockPositionStatus.PENDING -> {
                        logger.info { "Stock recovery: [${row.symbol.value}] entry order gone and shares not held — entry never filled" }
                        positions.save(
                            row.copy(
                                status = StockPositionStatus.ENTRY_UNFILLED,
                                closeReason = "recovery_entry_not_filled",
                                closedAt = Instant.now(clock),
                            ),
                        )
                        lifecycle.untrack(row.id)
                    }
                    else -> {
                        logger.warn {
                            "Stock recovery: [${row.symbol.value}] protective order gone and shares not held " +
                                "(${row.shares} expected, $available available) — exit filled while unwatched; " +
                                "closing administratively (NOT selling)"
                        }
                        positions.save(
                            row.copy(
                                status = StockPositionStatus.CLOSED_EXTERNAL,
                                closeReason = "recovery_exit_filled_externally",
                                closedAt = Instant.now(clock),
                            ),
                        )
                        lifecycle.untrack(row.id)
                        externallyClosed += "${row.symbol.value} ×${row.shares}"
                    }
                }
            }
            if (externallyClosed.isNotEmpty()) {
                alertPort.send(
                    AlertLevel.WARN,
                    "Stock positions closed externally: ${externallyClosed.size}",
                    "Exits filled at the broker while the engine was not watching (realized P&L unknown, " +
                        "check broker statements):\n" + externallyClosed.joinToString("\n"),
                )
            }
        }

    /**
     * Working protective sell-stops the broker holds for this symbol. Matched on symbol/side/type
     * because [AccountOpenOrder] carries no quantity —
     * enough to recognise "a stop is guarding this symbol", not enough to prove it guards exactly
     * these shares, which is why adoption below refuses to guess when there is more than one.
     */
    private fun workingStops(
        symbol: String,
        openOrders: List<AccountOpenOrder>,
    ) = openOrders.filter { it.symbol == symbol && it.action == "SELL" && it.orderType == "STP" }

    /**
     * Points [StockPosition.stopOrderId] back at the stop the broker is actually working.
     *
     * Before the AEP incident every re-arm persisted its own unacknowledged order id, so one spurious
     * pass overwrote the pointer to the real stop and the row could never recognise its own protection
     * again. The row is repaired here rather than only in [reprotect] because a row whose *target* is
     * still working never reaches that path — which is exactly how AEP stayed corrupted after the
     * re-arm loop itself had stopped.
     */
    private suspend fun repairStopOrderId(
        row: StockPosition,
        openOrders: List<AccountOpenOrder>,
        openOrderIds: Set<Int>,
    ): StockPosition {
        if (row.stopOrderId != null && row.stopOrderId in openOrderIds) return row
        val stops = workingStops(row.symbol.value, openOrders)
        val adopted = stops.singleOrNull() ?: return row
        if (adopted.orderId == row.stopOrderId) return row
        logger.warn {
            "Stock recovery: [${row.symbol.value}] stored stop ${row.stopOrderId} is not working but order " +
                "${adopted.orderId} is — repairing the row to track the live stop"
        }
        return positions.save(row.copy(stopOrderId = adopted.orderId))
    }

    /**
     * Shares are held with no protective order working — re-place the stop at the price the strategy
     * chose, which for these strategies never moves after entry (no trailing), so the stored value is
     * still the right one.
     *
     * Adopting an existing stop is tried first: a broker that has one working needs no second, and
     * submitting anyway is how a position ends up double-protected (a dip through the stop then sells
     * twice the shares and flips the position short).
     */
    private suspend fun reprotect(
        row: StockPosition,
        openOrders: List<AccountOpenOrder>,
        openOrderIds: Set<Int>,
    ) {
        val symbol = row.symbol.value
        val previous = lastReprotect[symbol]

        // The broker already guards these shares — adopt rather than pile on. Covers both a stop we
        // submitted and lost track of, and one placed by hand in TWS.
        val stops = workingStops(symbol, openOrders)
        if (stops.size > 1) {
            logger.error { "Stock recovery: [$symbol] ${stops.size} working stops — refusing to add another" }
            alertOnce(
                symbol,
                "$symbol has ${stops.size} working protective stops for ${row.shares} shares. A fill would sell more " +
                    "than the position holds and flip it short. Cancel the extras in TWS.",
            )
            return
        }
        val existing = stops.singleOrNull()
        if (existing != null) {
            lastReprotect.remove(symbol)
            alerted.remove(symbol)
            val adopted =
                positions.save(
                    row.copy(
                        status = StockPositionStatus.OPEN,
                        stopOrderId = existing.orderId,
                        openedAt = row.openedAt ?: Instant.now(clock),
                    ),
                )
            lifecycle.register(adopted)
            logger.info { "Stock recovery: [$symbol] adopted working stop ${existing.orderId} — no new order needed" }
            return
        }

        if (previous != null) {
            if (previous.orderId in openOrderIds) {
                // The broker took it after all; persist it now that it is real and stop counting.
                lastReprotect.remove(symbol)
                alerted.remove(symbol)
                lifecycle.register(positions.save(row.copy(stopOrderId = previous.orderId)))
                logger.info { "Stock recovery: [$symbol] stop ${previous.orderId} acknowledged — tracking it" }
                return
            }
            val waited = Duration.between(previous.at, Instant.now(clock))
            if (waited < REPROTECT_ACK_GRACE.toJavaDuration()) {
                logger.warn {
                    "Stock recovery: [$symbol] stop ${previous.orderId} submitted ${waited.toSeconds()}s ago has still " +
                        "not appeared in open orders — waiting for acknowledgement instead of submitting another"
                }
                return
            }
            if (previous.consecutiveUnacked >= REPROTECT_MAX_ATTEMPTS) {
                logger.error {
                    "Stock recovery: [$symbol] ${previous.consecutiveUnacked} unacknowledged stops — giving up, " +
                        "manual intervention required"
                }
                return
            }
            if (previous.consecutiveUnacked + 1 >= REPROTECT_ALERT_AFTER) {
                alertOnce(
                    symbol,
                    "${row.shares} shares of $symbol are held with NO working protective order. " +
                        "${previous.consecutiveUnacked + 1} stops have been submitted and none was ever acknowledged " +
                        "by the broker — automatic re-protection is not working. Place a stop or close the position in TWS.",
                )
            }
        }

        val newOrderId =
            runCatching { orders.submitProtectiveStop(row.symbol, row.shares, row.stopPrice) }
                .getOrElse { e ->
                    logger.error(e) { "Stock recovery: [$symbol] failed to re-place protective stop: ${e.message}" }
                    alertOnce(
                        symbol,
                        "${row.shares} shares of $symbol are held with no protective order and re-placing one " +
                            "failed: ${e.message}. Place a stop or close the position in TWS.",
                    )
                    return
                }
        // Deliberately NOT persisted to the row yet: an id the broker never acknowledges is not this
        // position's stop, and writing it over a good one is what made AEP unrecoverable. It is
        // adopted above on the pass that sees it in open orders.
        lastReprotect[symbol] =
            ReprotectAttempt(
                at = Instant.now(clock),
                orderId = newOrderId,
                consecutiveUnacked = (previous?.consecutiveUnacked ?: 0) + 1,
            )
        logger.warn { "Stock recovery: [$symbol] re-armed protective stop at ${row.stopPrice} (order $newOrderId)" }
    }

    /** One CRITICAL per run of failures — the old code re-alerted every pass for as long as it looped. */
    private suspend fun alertOnce(
        symbol: String,
        body: String,
    ) {
        if (!alerted.add(symbol)) return
        alertPort.send(AlertLevel.CRITICAL, "Cannot protect stock position: $symbol", body)
    }
}
