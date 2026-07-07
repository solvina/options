package cz.solvina.options.adapters.inbound.lifecycle

import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.domain.features.account.AccountPosition
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
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** How many times recovery polls the broker position feed before trusting/abandoning a snapshot. */
private const val RECOVERY_POSITION_POLLS = 5
private const val RECOVERY_POSITION_POLL_DELAY_MS = 500L

/**
 * Re-attaches the engine to flag positions it stopped watching.
 *
 * Flag fill detection is an in-memory watcher armed when the bracket order is placed — a restart
 * or an IBKR disconnect kills it while the GTC trailing stop lives on at the broker. The row then
 * stays PENDING/OPEN forever, and a later manual/EOD close would sell shares the broker no longer
 * holds (the 2026-07 short-stock-orphan incident). This service runs at startup and periodically:
 *
 *  - orders still working at the broker → re-arm the fill watchers;
 *  - entry filled while unwatched (protective child active, or shares held) → adopt as OPEN,
 *    re-placing the protective trailing stop if it is gone;
 *  - exit filled while unwatched (no orders, shares gone) → close as CLOSED_EXTERNAL, never sell;
 *  - entry expired without filling (no orders, no shares) → ENTRY_TIMEOUT.
 *
 * Rows whose orders have an armed watcher are skipped, so repeated runs are no-ops for healthy
 * positions.
 */
@Component
class FlagRecoveryService(
    private val flagPort: FlagPort,
    private val bracketOrderPort: BracketOrderPort,
    private val flagExecutionService: FlagExecutionService,
    private val openOrdersAdapter: IbkrOpenOrdersAdapter,
    private val positionsPort: PositionsPort,
    private val alertPort: AlertPort,
    private val connectionStatusPort: ConnectionStatusPort,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    @Scheduled(
        fixedDelayString = "\${flag-recovery.delay-ms:300000}",
        initialDelayString = "\${flag-recovery.initial-delay-ms:180000}",
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
            val rows = flagPort.findByStatus(FlagStatus.PENDING) + flagPort.findByStatus(FlagStatus.OPEN)
            val unwatched = rows.filter { row -> watchedIds(row).none { bracketOrderPort.hasActiveWatch(it) } }
            if (unwatched.isEmpty()) return@withLock

            logger.info { "Flag recovery: ${unwatched.size} PENDING/OPEN row(s) without an armed fill watcher" }
            val openOrderIds =
                runCatching { openOrdersAdapter.getOpenOrders() }
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
                    row.status == FlagStatus.PENDING && row.entryOrderId in openOrderIds -> {
                        logger.info {
                            "Flag recovery: [${row.symbol}] entry order ${row.entryOrderId} still working — re-arming entry watch"
                        }
                        flagExecutionService.launchEntryWatch(row, rewatch = true)
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
                        logger.info { "Flag recovery: [${row.symbol}] protective order still working — re-arming exit watch" }
                        flagExecutionService.launchExitWatch(open, rewatch = true)
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
                        reprotect(row)
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
     * The original trail distance is recoverable: profitTargetPrice = entryPrice + trailAmount by
     * construction.
     */
    private suspend fun reprotect(row: FlagPosition) {
        val trailAmount = row.profitTargetPrice.subtract(row.entryPrice)
        val newOrderId =
            runCatching { bracketOrderPort.submitTrailingStopSell(row.symbol, row.shares, row.stopLossPrice, trailAmount) }
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
        val updated =
            flagPort.update(row.copy(status = FlagStatus.OPEN, stopLossOrderId = newOrderId, profitTargetOrderId = newOrderId))
        logger.warn { "Flag recovery: [${row.symbol}] re-protected ${row.shares} shares with a new trailing stop (orderId=$newOrderId)" }
        alertPort.send(
            AlertLevel.WARN,
            "Flag position re-protected: ${row.symbol}",
            "${row.shares} shares of ${row.symbol} were held without a working protective order (it vanished while the " +
                "engine was not watching). A new trailing stop was placed (orderId=$newOrderId) and the position is managed again.",
        )
        flagExecutionService.launchExitWatch(updated, rewatch = true)
    }

    /** Order ids whose armed watcher marks this row as actively managed in-memory. */
    private fun watchedIds(row: FlagPosition) =
        when (row.status) {
            FlagStatus.PENDING -> setOf(row.entryOrderId, row.stopLossOrderId, row.profitTargetOrderId)
            else -> setOf(row.stopLossOrderId, row.profitTargetOrderId)
        }

    /**
     * Broker position snapshot trustworthy enough to base a close/adopt decision on. An empty
     * snapshot from a feed still warming up is indistinguishable from a flat account, so only a
     * non-empty snapshot (or exhausted retries ending in one) is returned; null means "do not
     * decide anything this run".
     */
    private suspend fun fetchPositionsSnapshot(): List<AccountPosition>? {
        repeat(RECOVERY_POSITION_POLLS) { attempt ->
            val snapshot =
                runCatching { positionsPort.getPositions() }
                    .onFailure { e -> logger.warn { "Flag recovery: position fetch failed (attempt $attempt): ${e.message}" } }
                    .getOrNull()
            if (!snapshot.isNullOrEmpty()) return snapshot
            if (attempt < RECOVERY_POSITION_POLLS - 1) delay(RECOVERY_POSITION_POLL_DELAY_MS)
        }
        return null
    }
}
