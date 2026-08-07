package cz.solvina.options.domain.features.strategy.live

import cz.solvina.options.domain.features.order.BrokerOrderUpdate
import cz.solvina.options.domain.features.order.OrderLifecyclePort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("STOCK_TRADES")

/**
 * Advances a [StockPosition] through its lifecycle from the broker's own order callbacks.
 *
 * [StockStrategyRunner] persists a PENDING row and submits the bracket; without this service that
 * row never moves again. Three consequences, all of which showed up as soon as the runner was
 * considered for a live paper session: the fill rate (the one number a backtest cannot produce —
 * see [cz.solvina.options.adapters.inbound.api.StockPositionApiController]) stays unmeasurable, an
 * expired day limit leaves a PENDING row forever, and because the runner refuses a second entry
 * while one is live, the first signal on a symbol would block that symbol permanently.
 *
 * Push-driven, like [cz.solvina.options.domain.features.flag.FlagExecutionService]: the broker's
 * order stream is the source of truth, never a poll. Restarts and disconnects drop the in-memory
 * registration, which is what [cz.solvina.options.adapters.inbound.lifecycle.StockRecoveryService]
 * exists to repair.
 */
@Service
class StockPositionLifecycleService(
    private val positions: StockPositionPort,
    private val orderLifecyclePort: OrderLifecyclePort,
    private val scope: CoroutineScope,
) {
    private val trackedOrders = ConcurrentHashMap<Int, UUID>()

    // Serializes handling: the flow collector and register()'s replayed updates run concurrently on
    // the same scope, and booking a close is a read-modify-write of the position row.
    private val updateMutex = Mutex()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { orderLifecyclePort.updates.collect(::onBrokerUpdate) }
    }

    /**
     * Starts (or resumes) tracking every order belonging to [position].
     *
     * Called right after submission and again by recovery after a restart. Any state the broker
     * already reported for those ids is replayed immediately, which closes the register/subscribe
     * race — an entry that filled between submission and registration is otherwise never seen.
     */
    fun register(position: StockPosition) {
        listOfNotNull(position.entryOrderId, position.stopOrderId, position.targetOrderId).forEach { orderId ->
            trackedOrders[orderId] = position.id
            orderLifecyclePort.current(orderId)?.let { update -> scope.launch { onBrokerUpdate(update) } }
        }
    }

    /** Drops tracking for a position, e.g. once it is terminal. */
    fun untrack(positionId: UUID) {
        trackedOrders.entries.removeIf { it.value == positionId }
    }

    private suspend fun onBrokerUpdate(update: BrokerOrderUpdate) {
        if (!trackedOrders.containsKey(update.orderId)) return // skip the lock for unrelated orders
        updateMutex.withLock { handleBrokerUpdate(update) }
    }

    private suspend fun handleBrokerUpdate(update: BrokerOrderUpdate) {
        val positionId = trackedOrders[update.orderId] ?: return
        val position = positions.findById(positionId)
        if (position == null || position.status.isTerminal) {
            untrack(positionId)
            return
        }
        when {
            update.orderId == position.entryOrderId && update.isFilled && position.status == StockPositionStatus.PENDING ->
                bookEntryFill(position, update.averageFillPrice, update.receivedAt)

            update.orderId == position.entryOrderId && update.isNonFilledTerminal && position.status == StockPositionStatus.PENDING ->
                bookEntryMiss(position, update.rejectionReason ?: "entry_not_filled", update.receivedAt)

            update.orderId == position.stopOrderId && update.isFilled && position.status == StockPositionStatus.OPEN ->
                bookExitFill(position, update.averageFillPrice, StockPositionStatus.CLOSED_STOP, "stop_hit", update.receivedAt)

            update.orderId == position.targetOrderId && update.isFilled && position.status == StockPositionStatus.OPEN ->
                bookExitFill(position, update.averageFillPrice, StockPositionStatus.CLOSED_TARGET, "target_hit", update.receivedAt)
        }
    }

    private suspend fun bookEntryFill(
        position: StockPosition,
        fillPrice: BigDecimal?,
        at: Instant,
    ) {
        val open =
            positions.save(
                position.copy(
                    status = StockPositionStatus.OPEN,
                    // Recorded verbatim. entrySlippage is derived from it against the signal price,
                    // and that difference is the live-vs-backtest measurement — averaging it away
                    // with the limit price would erase exactly what the paper session is for.
                    actualEntryPrice = fillPrice,
                    openedAt = at,
                ),
            )
        tradeLogger.info {
            "ENTRY_FILLED ${open.symbol.value} ${open.strategyId} entryOrder=${position.entryOrderId} " +
                "fill=$fillPrice signal=${position.signalPrice} slippage=${open.entrySlippage}"
        }
    }

    /**
     * The day limit died without filling. Terminal and first-class: the backtest fills every entry
     * it emits, so a miss is a divergence worth counting, not an error to swallow.
     *
     * The protective children are attached to this parent, so the broker cancels them with it —
     * cancelling them here would only produce error 202/10147 noise for orders that never activated.
     * Recovery re-checks that assumption against the broker's open orders.
     */
    private suspend fun bookEntryMiss(
        position: StockPosition,
        reason: String,
        at: Instant,
    ) {
        val missed =
            positions.save(
                position.copy(
                    status = StockPositionStatus.ENTRY_UNFILLED,
                    closeReason = reason,
                    closedAt = at,
                ),
            )
        untrack(missed.id)
        tradeLogger.info {
            "ENTRY_UNFILLED ${missed.symbol.value} ${missed.strategyId} entryOrder=${position.entryOrderId} " +
                "limit=${position.limitPrice} reason=$reason"
        }
    }

    /**
     * Books a close after a protective order filled.
     *
     * Which order id filled decides the status here — unlike the flag strategy, whose single
     * trailing stop exits winners and losers alike and therefore has to infer the outcome from the
     * P&L. A stop and a target are distinct orders, so the broker has already told us which one it
     * was. When no fill price was reported the order's own trigger is the best estimate available,
     * and the close reason says so rather than presenting an assumption as a fill.
     */
    private suspend fun bookExitFill(
        position: StockPosition,
        fillPrice: BigDecimal?,
        status: StockPositionStatus,
        reason: String,
        at: Instant,
    ) {
        val fallback = if (status == StockPositionStatus.CLOSED_TARGET) position.targetPrice else position.stopPrice
        val closePrice = fillPrice ?: fallback ?: position.stopPrice
        // The signal price, not the limit: an unfilled-price position cannot reach here (it would
        // still be PENDING), so actualEntryPrice is normally set; signalPrice is the last resort for
        // a row adopted by recovery without a reported fill.
        val effectiveEntry = position.actualEntryPrice ?: position.signalPrice
        val pnl =
            closePrice
                .subtract(effectiveEntry)
                .multiply(BigDecimal(position.shares))
                .setScale(2, RoundingMode.HALF_UP)
        val closed =
            positions.save(
                position.copy(
                    status = status,
                    closePrice = closePrice,
                    realizedPnl = pnl,
                    closeReason = reason + if (fillPrice == null) "_estimated" else "",
                    closedAt = at,
                ),
            )
        untrack(closed.id)
        tradeLogger.info {
            "${status.name} ${closed.symbol.value} ${closed.strategyId} fill=$closePrice entry=$effectiveEntry " +
                "shares=${closed.shares} reason=${closed.closeReason} pnl=\$$pnl"
        }
    }
}
