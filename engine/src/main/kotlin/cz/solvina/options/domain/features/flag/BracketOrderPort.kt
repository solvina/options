package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal

data class BracketOrderIds(
    val entryOrderId: Int,
    val stopLossOrderId: Int,
    val profitTargetOrderId: Int,
)

/**
 * Terminal outcome of an order: its status plus the broker-reported average fill price when
 * FILLED. [avgPrice] can be null even on a fill (the status callback carried no price) — callers
 * must fall back to an estimate, never to a theoretical order parameter.
 */
data class OrderFill(
    val status: OrderStatus,
    val avgPrice: BigDecimal? = null,
)

interface BracketOrderPort {
    /**
     * Submits an entry + trailing-stop exit (the backtest "best config": let winners run, no fixed
     * target, hold overnight):
     * - Parent: Stop-Market BUY at [entryPrice]
     * - Child: Trailing-Stop SELL — trailing distance [trailAmount], initial stop [stopLossPrice]
     *   (TRAIL order, GTC).
     *
     * Returns order IDs immediately. The single trailing protective order's id is returned as both
     * stopLossOrderId and profitTargetOrderId (so existing close logic cancels it).
     */
    suspend fun submitBracketOrder(
        symbol: Symbol,
        shares: Int,
        entryPrice: BigDecimal,
        stopLossPrice: BigDecimal,
        trailAmount: BigDecimal,
    ): BracketOrderIds

    /** Cancels the given order. Safe to call on already-cancelled orders (no-throw). */
    suspend fun cancelOrder(orderId: Int)

    /** Suspends until the parent entry order reaches a terminal state. Returns fill status + actual avg price. */
    suspend fun awaitParentFill(orderId: Int): OrderFill

    /** Suspends until a protective child order reaches a terminal state. Returns fill status + actual avg price. */
    suspend fun awaitChildFill(orderId: Int): OrderFill

    /**
     * Like [awaitParentFill] but for an entry order restored from persistence (placed by a previous
     * engine run and confirmed still working at the broker): re-arms the fill watch first instead of
     * expecting one registered at placement.
     */
    suspend fun rewatchParentFill(orderId: Int): OrderFill

    /** Like [awaitChildFill] but for a protective order restored from persistence — re-arms the watch first. */
    suspend fun rewatchChildFill(orderId: Int): OrderFill

    /** True while a fill watcher is armed for [orderId]. Lets recovery skip positions already being watched. */
    fun hasActiveWatch(orderId: Int): Boolean

    /**
     * Places a standalone GTC trailing-stop SELL to re-protect shares whose original protective
     * order vanished (e.g. cancelled while the engine was down). Returns the new order ID.
     */
    suspend fun submitTrailingStopSell(
        symbol: Symbol,
        shares: Int,
        initialStop: BigDecimal,
        trailAmount: BigDecimal,
    ): Int

    /**
     * Places an immediate market SELL for [shares] of [symbol].
     * Used for EOD liquidation and manual closes of OPEN positions.
     * Returns the new order ID.
     */
    suspend fun submitMarketSell(
        symbol: Symbol,
        shares: Int,
    ): Int
}
