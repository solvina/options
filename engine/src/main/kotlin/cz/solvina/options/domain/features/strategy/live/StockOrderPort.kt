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

    suspend fun cancelOrder(orderId: Int)
}
