package cz.solvina.options.domain.features.strategy.live

import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Lifecycle of a live stock-strategy position.
 *
 * [ENTRY_UNFILLED] is terminal and deliberately first-class: the backtest fills every emitted entry
 * at the next bar's open, a live day limit does not, and the gap between those two facts is only
 * measurable if the misses are recorded. See the v37 migration header.
 */
enum class StockPositionStatus {
    /** Entry limit is working at the broker. */
    PENDING,

    /** Entry filled; protective orders are live. */
    OPEN,

    /** The day limit expired without filling — a real divergence from the backtest. */
    ENTRY_UNFILLED,

    CLOSED_TARGET,
    CLOSED_STOP,
    CLOSED_MANUAL,

    /** Position vanished from the broker without our order closing it. */
    CLOSED_EXTERNAL,
    ;

    val isTerminal: Boolean get() = this != PENDING && this != OPEN
    val isLive: Boolean get() = this == PENDING || this == OPEN
}

/**
 * One position produced by one strategy under one assignment.
 *
 * [strategyId] and [assignmentId] are non-null by construction — attribution is the decision the
 * plan flags as unrecoverable after the fact. [paramsJson] snapshots the resolved parameters at
 * entry because the assignment row can be edited later; the pointer alone would not reproduce the
 * decision that was actually made.
 */
data class StockPosition(
    val id: UUID,
    val strategyId: String,
    val assignmentId: UUID,
    val paramsJson: String,
    val symbol: Symbol,
    val timeframe: Timeframe,
    val status: StockPositionStatus,
    val entryOrderId: Int? = null,
    val stopOrderId: Int? = null,
    val targetOrderId: Int? = null,
    val closeOrderId: Int? = null,
    /** The bar close the decision was made on — what the backtest would have used as its signal. */
    val signalPrice: BigDecimal,
    /** open + tolerance; the price we were actually willing to pay. */
    val limitPrice: BigDecimal,
    val stopPrice: BigDecimal,
    val targetPrice: BigDecimal? = null,
    val shares: Int,
    val riskAmount: BigDecimal? = null,
    val actualEntryPrice: BigDecimal? = null,
    val closePrice: BigDecimal? = null,
    val realizedPnl: BigDecimal? = null,
    val closeReason: String? = null,
    val highestPriceSeen: BigDecimal? = null,
    val lowestPriceSeen: BigDecimal? = null,
    val signalledAt: Instant,
    val openedAt: Instant? = null,
    val closedAt: Instant? = null,
) {
    /** Slippage against the signal price — the live-vs-backtest number worth watching. */
    val entrySlippage: BigDecimal?
        get() = actualEntryPrice?.subtract(signalPrice)
}
