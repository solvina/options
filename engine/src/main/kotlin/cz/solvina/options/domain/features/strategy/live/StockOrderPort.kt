package cz.solvina.options.domain.features.strategy.live

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal

/** Ids reserved for one entry and its protective children. */
data class StockOrderIds(
    val entryOrderId: Int,
    val stopOrderId: Int,
    val targetOrderId: Int,
)

/**
 * Order placement for stock strategies.
 *
 * Deliberately NOT reusing [cz.solvina.options.domain.features.flag.BracketOrderPort]: the flag
 * strategy enters on a **stop** above the market because it is buying a breakout. These strategies
 * buy a turn from below, so a stop entry would systematically buy the worst price of the move and
 * invert the premise. The entry here is a day **limit**.
 */
interface StockOrderPort {
    fun reserveOrderIds(): StockOrderIds

    /**
     * Day LMT BUY at [limitPrice], with an OCA-grouped GTC stop (and optional GTC limit target)
     * attached as children so protection exists at the broker the instant the entry fills.
     *
     * GTC is the point: the position must survive this process dying. That is the direct lesson
     * from 2026-07-24, when nothing reached TWS for a whole session and the engine never noticed.
     *
     * The entry is DAY: an unfilled limit must expire rather than sit and fill days later against a
     * signal that has gone stale. An expiry is recorded as [StockPositionStatus.ENTRY_UNFILLED].
     */
    suspend fun submitLimitEntryWithProtection(
        ids: StockOrderIds,
        symbol: Symbol,
        shares: Int,
        limitPrice: BigDecimal,
        stopPrice: BigDecimal,
        targetPrice: BigDecimal?,
    ): StockOrderIds

    /**
     * A standalone GTC stop SELL for shares already held, returning the new order id.
     *
     * Recovery's repair path: shares are at the broker with no working protective order, because
     * the OCA pair was cancelled, rejected, or lost with a disconnect. Not part of a bracket — there
     * is no parent left to attach to — and no target is re-placed alongside it, since an unprotected
     * position needs its downside covered first and a missing target only forgoes profit.
     */
    suspend fun submitProtectiveStop(
        symbol: Symbol,
        shares: Int,
        stopPrice: BigDecimal,
    ): Int

    suspend fun cancelOrder(orderId: Int)
}
