package cz.solvina.options.domain.features.order

import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal

/**
 * Fresh per-leg NBBO quotes, used by exchanges that submit leg-by-leg (e.g. EUREX) to price
 * each leg from its own book rather than splitting a net credit. Atomic-combo exchanges ignore it.
 */
data class LegQuotes(
    val soldBid: BigDecimal,
    val soldAsk: BigDecimal,
    val boughtBid: BigDecimal,
    val boughtAsk: BigDecimal,
)

/**
 * Port for non-blocking, tick-aware combo order execution.
 *
 * US exchanges place a single IBKR BAG (combo) order covering both legs atomically. EUREX-style
 * exchanges have no combo book, so the adapter legs in (protective long first, then short) and only
 * reports success once BOTH legs are confirmed filled — never leaving a naked short. Price
 * adjustments are done via cancel-and-replace.
 */
interface OrderExecutionPort {
    /**
     * Submit a spread entry: SELL [soldContract] + BUY [boughtContract] for net [netCredit].
     *
     * US: single BAG order, returns the orderId immediately without waiting for fill.
     * EUREX leg-by-leg: blocks until both legs are filled (returning a settled order id) or aborts;
     * a stranded protective long throws [StrandedLongLegException]. [legQuotes] supplies fresh
     * per-leg prices for the leg-by-leg path; atomic-combo exchanges ignore it.
     */
    suspend fun submitComboLimitOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
        legQuotes: LegQuotes? = null,
    ): Int

    /** Suspend until the order fills, is cancelled, or errors. */
    suspend fun awaitFill(orderId: Int): OrderStatus

    /** Cancel the order and wait for IBKR confirmation (up to 10 s). */
    suspend fun cancelAndAwait(orderId: Int)

    /**
     * Cancel the existing combo order and resubmit at [newCredit].
     * Returns the new orderId.
     */
    suspend fun replaceComboWithNewPrice(
        existingOrderId: Int,
        soldContract: OptionContract,
        boughtContract: OptionContract,
        newCredit: Money,
        qty: Int,
    ): Int

    /** Returns the set of underlying symbols that have at least one open order on the broker. */
    suspend fun getSymbolsWithOpenOrders(): Set<Symbol>
}
