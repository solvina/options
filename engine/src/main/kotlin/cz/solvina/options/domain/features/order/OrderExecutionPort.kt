package cz.solvina.options.domain.features.order

import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol

/**
 * Port for non-blocking, tick-aware combo order execution.
 *
 * All orders are placed as IBKR BAG (combo) orders covering both legs atomically —
 * no legging risk. Price adjustments are done via cancel-and-replace.
 */
interface OrderExecutionPort {
    /**
     * Submit a BAG/combo LMT order: SELL [soldContract] + BUY [boughtContract] for net [netCredit].
     * Returns the orderId immediately without waiting for fill.
     */
    suspend fun submitComboLimitOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
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
