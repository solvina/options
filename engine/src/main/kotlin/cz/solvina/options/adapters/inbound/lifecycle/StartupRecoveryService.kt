package cz.solvina.options.adapters.inbound.lifecycle

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOpenOrdersAdapter
import cz.solvina.options.adapters.outbound.ibkr.account.OpenOrder
import cz.solvina.options.adapters.outbound.ibkr.order.OrderCancellationService
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.spread.BearCallSpreadPort
import cz.solvina.options.domain.features.spread.BullPutSpreadPort
import cz.solvina.options.domain.features.spread.model.BearCallSpread
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
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
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** How many times recovery polls the broker position feed before deciding a leg is not held. */
private const val RECOVERY_POSITION_POLLS = 5

/** Delay between recovery position polls — lets a feed that is still warming up at startup populate. */
private const val RECOVERY_POSITION_POLL_DELAY_MS = 500L

/**
 * Periodic recovery only touches PENDING rows at least this old. A younger row may still have a
 * LIVE entry loop laddering it (the loop replaces order ids, so an armed-watch check alone cannot
 * prove the row is abandoned) — meddling with it would cancel/adopt an entry mid-chase.
 */
private val PERIODIC_MIN_PENDING_AGE: Duration = Duration.ofMinutes(60)

@Component
class StartupRecoveryService(
    private val spreadPort: BullPutSpreadPort,
    private val bearCallPort: BearCallSpreadPort,
    private val orderRegistry: IbkrOrderRegistry,
    private val openOrdersAdapter: IbkrOpenOrdersAdapter,
    private val client: EClientSocket,
    private val orderCancellationService: OrderCancellationService,
    private val positionsPort: PositionsPort,
    private val connectionStatusPort: ConnectionStatusPort,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /**
     * Periodic re-evaluation of PENDING rows, so a row left PENDING (e.g. its fill watcher was
     * flushed by a disconnect) is resolved within minutes instead of waiting for the next restart.
     * The MU 860/850 incident (2026-07-09): a flushed watcher was misread as a broker cancel, the
     * row was written off, the order stayed working at IBKR and filled — an untracked live spread.
     */
    @Scheduled(
        fixedDelayString = "\${spread-recovery.delay-ms:300000}",
        initialDelayString = "\${spread-recovery.initial-delay-ms:240000}",
    )
    fun scheduledRecoverPending() {
        if (!connectionStatusPort.isConnected()) {
            logger.debug { "Spread recovery skipped: IBKR not connected" }
            return
        }
        scope.launch {
            runCatching { recoverPending(startup = false) }
                .onFailure { e -> logger.error(e) { "Periodic spread recovery failed: ${e.message}" } }
        }
    }

    suspend fun recover() {
        recoverClosing()
        recoverPending(startup = true)
    }

    private suspend fun recoverClosing() {
        val closingSpreads = spreadPort.findByStatus(SpreadStatus.CLOSING) + bearCallPort.findByStatus(SpreadStatus.CLOSING)
        if (closingSpreads.isEmpty()) return

        val openOrders =
            runCatching { openOrdersAdapter.getOpenOrders() }
                .onFailure { e -> logger.warn(e) { "Recovery: could not fetch open IBKR orders" } }
                .getOrDefault(emptyList<OpenOrder>())

        // Cancel any orphaned orders for symbols stuck in CLOSING state.
        // BUY orders: placed by the previous engine run's close attempt; without cancellation
        //            the next retryClose would place a duplicate order for the same contract.
        // SELL orders: orphaned entry orders from failed trades; these can execute when the close
        //             completes, reversing the position (e.g., AMD bug: +18 LONG closed but -18 SHORT
        //             opened due to stale SELL orders filling simultaneously).
        run {
            val closingSymbols = closingSpreads.map { it.symbol.value }.toSet()
            val staleBuyOrders = openOrders.filter { it.symbol in closingSymbols && it.action.equals("BUY", ignoreCase = true) }
            val staleSellOrders = openOrders.filter { it.symbol in closingSymbols && it.action.equals("SELL", ignoreCase = true) }
            val staleOrders = staleBuyOrders + staleSellOrders

            if (staleOrders.isNotEmpty()) {
                logger.info {
                    "Recovery: atomically cancelling ${staleBuyOrders.size} stale BUY + ${staleSellOrders.size} stale SELL orders"
                }

                val buyOrderIds = staleBuyOrders.map { it.orderId }
                if (buyOrderIds.isNotEmpty()) {
                    val buyResults = orderCancellationService.cancelOrdersAtomic(buyOrderIds, "recovery_stale_buy")
                    for (result in buyResults) {
                        if (result.success) {
                            logger.info { "Recovery: stale BUY order ${result.orderId} cancelled" }
                        } else {
                            logger.warn { "Recovery: stale BUY order ${result.orderId} cancellation failed: ${result.reason}" }
                        }
                    }
                }

                val sellOrderIds = staleSellOrders.map { it.orderId }
                if (sellOrderIds.isNotEmpty()) {
                    val sellResults = orderCancellationService.cancelOrdersAtomic(sellOrderIds, "recovery_stale_sell")
                    for (result in sellResults) {
                        if (result.success) {
                            logger.info { "Recovery: stale SELL order ${result.orderId} cancelled — prevents position reversal" }
                        } else {
                            logger.warn {
                                "Recovery: stale SELL order ${result.orderId} cancellation failed: ${result.reason} — position reversal risk"
                            }
                        }
                    }
                }
            } else {
                logger.info { "Recovery: ${closingSpreads.size} CLOSING spread(s) found — no orphaned orders to cancel" }
            }
        }
    }

    private suspend fun recoverPending(startup: Boolean) =
        mutex.withLock {
            val cutoff = Instant.now().minus(PERIODIC_MIN_PENDING_AGE)
            val pendingSpreads =
                (spreadPort.findByStatus(SpreadStatus.PENDING) + bearCallPort.findByStatus(SpreadStatus.PENDING))
                    .filter { startup || it.openedAt < cutoff }
            if (pendingSpreads.isEmpty()) return@withLock

            // No trustworthy open-orders list → no verdicts this pass. Treating a failed fetch as
            // "no orders" would route every row to the not-found branch and decide on bad data.
            val openOrderIds =
                runCatching { openOrdersAdapter.getOpenOrders() }
                    .onFailure { e -> logger.warn(e) { "Recovery: could not fetch open IBKR orders — skipping PENDING pass" } }
                    .getOrNull()
                    ?.map { it.orderId }
                    ?.toSet() ?: return@withLock
            logger.info { "Recovery: found ${pendingSpreads.size} PENDING spread(s)${if (startup) "" else " (periodic pass)"}" }

            processPending(pendingSpreads, openOrderIds)
        }

    private suspend fun processPending(
        pendingSpreads: List<Spread>,
        openOrderIds: Set<Int>,
    ) {
        for (spread in pendingSpreads) {
            val orderId = spread.soldLeg.orderId
            if (orderId == 0) {
                logger.warn { "Recovery: PENDING spread ${spread.id} has no orderId, closing as unknown" }
                persist(
                    statusChanged(spread, SpreadStatus.CLOSED_REJECTED, "recovery_no_order", Instant.now()),
                )
                continue
            }

            if (orderId in openOrderIds) {
                if (orderRegistry.hasActiveWatch(orderId)) {
                    logger.debug { "Recovery: orderId=$orderId for ${spread.symbol} already has an armed watcher — skipping" }
                    continue
                }
                // Order still working at the broker — arm a fresh fill watch. A fill that already
                // landed this session is caught via isFilled (recorded before deferreds complete).
                orderRegistry.ensureWatch(orderId)
                if (orderRegistry.isFilled(orderId)) {
                    orderRegistry.pendingOrderStatus.remove(orderId)
                    persist(statusChanged(spread, SpreadStatus.OPEN))
                    logger.warn { "Recovery: orderId=$orderId already FILLED — spread ${spread.id} promoted to OPEN" }
                    continue
                }
                val deferred = orderRegistry.pendingOrderStatus[orderId] ?: continue
                logger.info {
                    "Recovery: re-registered orderId=$orderId for ${spread.symbol} " +
                        "${spread.soldLeg.contract.strike}${spread.soldLeg.contract.type.ibkrCode}/" +
                        "${spread.boughtLeg.contract.strike}${spread.boughtLeg.contract.type.ibkrCode}"
                }

                scope.launch {
                    val result = runCatching { deferred.await() }
                    when {
                        result.getOrNull() == OrderStatus.FILLED || orderRegistry.isFilled(orderId) -> {
                            persist(statusChanged(spread, SpreadStatus.OPEN))
                            logger.info { "Recovery: orderId=$orderId filled — spread ${spread.id} promoted to OPEN" }
                        }
                        result.isFailure -> {
                            // Exceptional completion (disconnect flush, broker error) is NOT a broker
                            // cancel — the order may still be working. Writing the row off here is how
                            // MU 860/850 became an untracked live spread (2026-07-09): the order stayed
                            // live at IBKR and filled after the row was closed. Leave PENDING; the
                            // periodic pass re-evaluates against the broker's actual order/position state.
                            logger.warn {
                                "Recovery: fill watch for orderId=$orderId (${spread.symbol}) completed exceptionally " +
                                    "(${result.exceptionOrNull()?.message}) — leaving PENDING for re-evaluation, NOT closing"
                            }
                        }
                        else -> {
                            // Broker-confirmed terminal cancel (onOrderStatus/onError completed the
                            // deferred normally) — safe to write the attempt off.
                            persist(
                                statusChanged(
                                    spread,
                                    SpreadStatus.CLOSED_TIMEOUT,
                                    "recovered_cancelled",
                                    Instant.now(),
                                ),
                            )
                            logger.info { "Recovery: orderId=$orderId cancelled/rejected — spread ${spread.id} closed" }
                        }
                    }
                    orderRegistry.pendingOrderStatus.remove(orderId)
                }
            } else {
                // Order not found in IBKR — it filled or was cancelled while the engine was down.
                // Do NOT blindly close: if BOTH legs are actually held at the broker the order filled,
                // so adopt the spread as OPEN (else it becomes an unmanaged orphan). Crucially we must
                // be able to TRUST the position snapshot before concluding "not held": a fetch that
                // throws — or an empty snapshot from a feed that has not warmed up yet at startup — is
                // NOT evidence the legs are flat. Treating it as such false-closes a live position
                // (phantom close + de-managed real risk), so we only decide on a trustworthy snapshot.
                val positions = fetchPositionsForRecovery(spread)
                when {
                    positions == null -> {
                        // Never got a usable snapshot — leave PENDING so the next startup / reconciliation
                        // re-evaluates, rather than false-closing a position we simply could not observe.
                        logger.warn {
                            "Recovery: orderId=$orderId for ${spread.symbol} vanished from open orders but positions " +
                                "could not be fetched — leaving PENDING for re-evaluation (not closing)"
                        }
                    }
                    bothLegsHeld(spread, positions) -> {
                        persist(statusChanged(spread, SpreadStatus.OPEN))
                        logger.warn {
                            "Recovery: orderId=$orderId for ${spread.symbol} vanished from open orders but BOTH legs are " +
                                "held — adopting spread ${spread.id} as OPEN so it gets managed"
                        }
                    }
                    else -> {
                        logger.warn {
                            "Recovery: orderId=$orderId for ${spread.symbol} not in open orders and legs confirmed not held — " +
                                "closing as recovery_unknown (any stranded leg will be flagged by reconciliation)"
                        }
                        persist(
                            statusChanged(
                                spread,
                                SpreadStatus.CLOSED_RECOVERY_UNKNOWN,
                                "recovery_unknown",
                                Instant.now(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Fetch a broker position snapshot trustworthy enough to base a recovery close on, tolerating a
     * feed that is still warming up at startup. Polls up to [RECOVERY_POSITION_POLLS] times and stops
     * early on the first authoritative snapshot (one that shows this spread's legs, or is simply
     * non-empty — a warm feed listing other positions is proof these legs are flat). Returns the last
     * successful snapshot otherwise, or null only when EVERY attempt threw — in which case the caller
     * must NOT treat the spread as flat.
     */
    private suspend fun fetchPositionsForRecovery(spread: Spread): List<AccountPosition>? {
        var last: List<AccountPosition>? = null
        repeat(RECOVERY_POSITION_POLLS) { attempt ->
            val snapshot =
                runCatching { positionsPort.getPositions() }
                    .onFailure { e -> logger.warn(e) { "Recovery: could not fetch positions for ${spread.symbol} (attempt $attempt)" } }
                    .getOrNull()
            if (snapshot != null) {
                last = snapshot
                if (bothLegsHeld(spread, snapshot) || snapshot.isNotEmpty()) return snapshot
            }
            if (attempt < RECOVERY_POSITION_POLLS - 1) delay(RECOVERY_POSITION_POLL_DELAY_MS)
        }
        return last
    }

    /** True if both legs of [spread] are present at the broker with the expected signed quantities. */
    private fun bothLegsHeld(
        spread: Spread,
        positions: List<AccountPosition>,
    ): Boolean {
        val shortHeld = positions.any { legMatches(it, spread, isShort = true) }
        val longHeld = positions.any { legMatches(it, spread, isShort = false) }
        return shortHeld && longHeld
    }

    private fun legMatches(
        p: AccountPosition,
        spread: Spread,
        isShort: Boolean,
    ): Boolean {
        val leg = if (isShort) spread.soldLeg else spread.boughtLeg
        val c = leg.contract
        val expectedQty = if (isShort) -spread.quantity else spread.quantity
        // compareTo, not equals: BigDecimal equality is scale-sensitive and the broker feed reports
        // strikes as 280.0 while the DB stores 280.00 — equals() would false-negative every leg and
        // route actually-filled spreads to recovery_unknown.
        return p.secType == "OPT" &&
            p.symbol == c.symbol.value &&
            p.strike?.compareTo(c.strike) == 0 &&
            p.optionRight == c.type.ibkrCode &&
            p.expiry == c.expiry &&
            p.quantity.toInt() == expectedQty
    }

    /** Route a status write to the correct strategy's port — the sealed [Spread] type keeps this exhaustive. */
    private suspend fun persist(spread: Spread): Spread =
        when (spread) {
            is BullPutSpread -> spreadPort.update(spread)
            is BearCallSpread -> bearCallPort.update(spread)
        }

    /** Strategy-agnostic status change: copies the row with a new status, dispatched over the sealed type. */
    private fun statusChanged(
        spread: Spread,
        status: SpreadStatus,
        closeReason: String? = spread.closeReason,
        closedAt: Instant? = spread.closedAt,
    ): Spread =
        when (spread) {
            is BullPutSpread -> spread.copy(status = status, closeReason = closeReason, closedAt = closedAt)
            is BearCallSpread -> spread.copy(status = status, closeReason = closeReason, closedAt = closedAt)
        }
}
