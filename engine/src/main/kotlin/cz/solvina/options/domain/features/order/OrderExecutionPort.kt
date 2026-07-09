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

    /**
     * Cancel the order and wait for IBKR confirmation (up to 10 s).
     *
     * Returns the order's true terminal status: [OrderStatus.FILLED] when the fill raced the cancel
     * (the order is a real position — the caller must honor it, not record the entry as aborted),
     * [OrderStatus.CANCELLED] otherwise.
     */
    suspend fun cancelAndAwait(orderId: Int): OrderStatus

    /**
     * Cancel the existing combo order and resubmit at [newCredit]. Returns the new orderId, or
     * [existingOrderId] unchanged when the old order turned out to have FILLED during the cancel
     * (no replacement submitted — the caller's existing fill watcher will complete).
     *
     * Throws [OrderReplacementUnverifiedException] when the old order could not be confirmed removed:
     * no replacement is submitted (double-fill is prevented), and the caller MUST catch this and ride
     * the existing order to fill/timeout rather than let it crash the entry (a leaked PENDING spread
     * with a live order is how orphans accumulate).
     *
     * Used only by exchanges without a combo book (EUREX leg-by-leg). US atomic-combo exchanges
     * amend in place via [modifyComboPriceInPlace] — see [supportsInPlaceComboModify].
     */
    suspend fun replaceComboWithNewPrice(
        existingOrderId: Int,
        soldContract: OptionContract,
        boughtContract: OptionContract,
        newCredit: Money,
        qty: Int,
    ): Int

    /**
     * True when the exchange serving [symbol] supports amending a live combo order's limit price in
     * place (a single atomic BAG order). When true, the ladder MUST amend via
     * [modifyComboPriceInPlace] rather than cancel-and-replace: an in-place amend issues no cancel,
     * so it cannot hit the PendingCancel-verification race that makes the cancel path falsely abort a
     * healthy entry as ORDER_REJECTED. Defaults to false (leg-by-leg exchanges keep cancel-replace).
     */
    fun supportsInPlaceComboModify(symbol: Symbol): Boolean = false

    /**
     * Amend the working combo order [existingOrderId] to rest at [newCredit], IN PLACE — same
     * orderId, same fill watcher, no cancel. IBKR treats a re-`placeOrder` on a live id as a
     * modification. Only valid for exchanges where [supportsInPlaceComboModify] is true.
     */
    suspend fun modifyComboPriceInPlace(
        existingOrderId: Int,
        soldContract: OptionContract,
        boughtContract: OptionContract,
        newCredit: Money,
        qty: Int,
    ): Unit = throw UnsupportedOperationException("in-place combo modify not supported")

    /** Returns the set of underlying symbols that have at least one open order on the broker. */
    suspend fun getSymbolsWithOpenOrders(): Set<Symbol>

    /**
     * Consume (and clear) the broker-reported average fill price for [orderId], if the order status
     * callback carried one. Null when no fill price was reported (e.g. the order didn't fill, or the
     * broker sent no avgFillPrice). One-shot: a second call for the same orderId returns null.
     */
    fun consumeFillPrice(orderId: Int): BigDecimal?

    /**
     * Consume (and clear) the broker-reported rejection reason for [orderId] — the IBKR error code and
     * message behind a CANCELLED/rejected status — so the caller can log *why* an entry was rejected.
     * Null when the broker reported no reason (e.g. a normal fill, or our own reprice cancel). One-shot.
     *
     * Diagnostic-only (it never affects recorded P&L), so it defaults to null — only the live IBKR
     * adapter overrides it; test doubles and the backtest adapter can ignore it.
     */
    fun consumeRejectReason(orderId: Int): String? = null

    /**
     * True if WE initiated the cancel of [orderId] (a reprice or abort), not the broker rejecting it.
     * The execution loop consults this so a terminal CANCELLED it caused itself is not misreported as
     * an ORDER_REJECTED (which mislabels the spread CLOSED_REJECTED and implies a broker fault that
     * isn't there). Defaults to false; only the live IBKR adapter overrides it.
     */
    fun wasSelfCancelled(orderId: Int): Boolean = false
}
