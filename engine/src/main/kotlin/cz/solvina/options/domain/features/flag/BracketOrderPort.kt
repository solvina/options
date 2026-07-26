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
    /** Reserves consecutive broker ids without sending anything to TWS. */
    fun reserveBracketOrderIds(): BracketOrderIds = error("Broker order-id reservation is not implemented")

    /** Reserves one broker id without sending anything to TWS. */
    fun reserveOrderId(): Int = error("Broker order-id reservation is not implemented")

    /** Sends a bracket whose ids have already been persisted by the caller. */
    suspend fun submitBracketOrder(
        ids: BracketOrderIds,
        symbol: Symbol,
        shares: Int,
        entryPrice: BigDecimal,
        stopLossPrice: BigDecimal,
        trailAmount: BigDecimal,
    ): BracketOrderIds = submitBracketOrder(symbol, shares, entryPrice, stopLossPrice, trailAmount)

    /** Compatibility seam for backtests; live implementations override the id-aware overload. */
    @Deprecated("Use reserveBracketOrderIds plus the id-aware submitBracketOrder")
    suspend fun submitBracketOrder(
        symbol: Symbol,
        shares: Int,
        entryPrice: BigDecimal,
        stopLossPrice: BigDecimal,
        trailAmount: BigDecimal,
    ): BracketOrderIds = error("Bracket submission is not implemented")

    /** Cancels the given order. Safe to call on already-cancelled orders (no-throw). */
    suspend fun cancelOrder(orderId: Int)

    @Deprecated("Order lifecycle is delivered through OrderLifecyclePort")
    suspend fun awaitParentFill(orderId: Int): OrderFill = error("Use OrderLifecyclePort")

    @Deprecated("Order lifecycle is delivered through OrderLifecyclePort")
    suspend fun awaitChildFill(orderId: Int): OrderFill = error("Use OrderLifecyclePort")

    @Deprecated("Order lifecycle is delivered through OrderLifecyclePort")
    suspend fun rewatchParentFill(orderId: Int): OrderFill = awaitParentFill(orderId)

    @Deprecated("Order lifecycle is delivered through OrderLifecyclePort")
    suspend fun rewatchChildFill(orderId: Int): OrderFill = awaitChildFill(orderId)

    @Deprecated("Order lifecycle is delivered through OrderLifecyclePort")
    fun hasActiveWatch(orderId: Int): Boolean = false

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
     * Places an immediate market SELL for [shares] of [symbol] using a broker id that the caller has
     * already persisted. Returns immediately; terminal status and fill price must be consumed from
     * OrderLifecyclePort.
     */
    suspend fun submitMarketSell(
        orderId: Int,
        symbol: Symbol,
        shares: Int,
    ): Int

    suspend fun submitMarketSell(
        symbol: Symbol,
        shares: Int,
    ): Int {
        val orderId = reserveOrderId()
        return submitMarketSell(orderId, symbol, shares)
    }
}
