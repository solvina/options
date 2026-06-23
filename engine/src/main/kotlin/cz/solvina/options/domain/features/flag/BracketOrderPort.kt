package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal

data class BracketOrderIds(
    val entryOrderId: Int,
    val stopLossOrderId: Int,
    val profitTargetOrderId: Int,
)

data class EntryFill(
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
    suspend fun awaitParentFill(orderId: Int): EntryFill

    /** Suspends until a child order (SL or PT) reaches a terminal state. */
    suspend fun awaitChildFill(orderId: Int): OrderStatus

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
