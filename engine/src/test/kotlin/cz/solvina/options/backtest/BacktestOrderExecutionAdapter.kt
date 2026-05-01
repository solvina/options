package cz.solvina.options.backtest

import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import java.util.concurrent.atomic.AtomicInteger

/**
 * Backtest implementation of [OrderExecutionPort].
 *
 * Uses an optimistic fill model: [submitComboLimitOrder] immediately records the combo
 * and [awaitFill] returns [OrderStatus.FILLED] synchronously. This mirrors
 * [BacktestOrderAdapter]'s behaviour for single-leg orders.
 *
 * [cancelAndAwait] is a no-op — in the backtest every order fills on the first attempt
 * so cancellation should never be triggered.
 */
class BacktestOrderExecutionAdapter : OrderExecutionPort {
    private val nextOrderId = AtomicInteger(1)

    /** All submitted combo orders — for inspection in tests or P&L accounting. */
    val comboFills = mutableListOf<ComboFillRecord>()

    override suspend fun submitComboLimitOrder(
        soldContract: OptionContract,
        boughtContract: OptionContract,
        netCredit: Money,
        qty: Int,
    ): Int {
        val orderId = nextOrderId.getAndIncrement()
        comboFills.add(
            ComboFillRecord(
                orderId = orderId,
                soldContract = soldContract,
                boughtContract = boughtContract,
                netCredit = netCredit,
                qty = qty,
            ),
        )
        return orderId
    }

    override suspend fun awaitFill(orderId: Int): OrderStatus = OrderStatus.FILLED

    override suspend fun cancelAndAwait(orderId: Int) {
        // No-op — all fills are immediate in backtest
    }

    override suspend fun replaceComboWithNewPrice(
        existingOrderId: Int,
        soldContract: OptionContract,
        boughtContract: OptionContract,
        newCredit: Money,
        qty: Int,
    ): Int = submitComboLimitOrder(soldContract, boughtContract, newCredit, qty)
}

data class ComboFillRecord(
    val orderId: Int,
    val soldContract: OptionContract,
    val boughtContract: OptionContract,
    val netCredit: Money,
    val qty: Int,
)
