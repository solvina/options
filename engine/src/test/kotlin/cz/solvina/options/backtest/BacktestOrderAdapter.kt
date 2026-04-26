package cz.solvina.options.backtest

import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.LegOrder
import cz.solvina.options.domain.features.order.OrderPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simulates immediate fills for backtesting.
 *
 * Fill model:
 *   SELL leg: fills at the bid  (pessimistic — we give up the spread to the market-maker)
 *   BUY  leg: fills at the ask  (pessimistic — same reasoning)
 *
 * In practice the backtest uses mid prices for credit/risk calculations
 * (matching how ScannerService builds the spread), so the filled price
 * here only matters if the caller inspects it. Current flow does not,
 * so any non-null fill is sufficient for the FILLED status gate.
 *
 * Commission: [commissionPerContract] per contract per leg, deducted
 * by the [BacktestEngine] when it updates the account balance.
 */
class BacktestOrderAdapter(
    val commissionPerContract: Double = 0.65,
) : OrderPort {
    private val nextOrderId = AtomicInteger(1)

    /** Recorded fill history — inspected by [BacktestEngine] for P&L accounting. */
    val fills = mutableListOf<FillRecord>()

    override suspend fun placeAndAwaitFill(
        contract: OptionContract,
        action: LegAction,
        limitPrice: Money,
        qty: Int,
    ): LegOrder {
        val orderId = nextOrderId.getAndIncrement()
        fills.add(FillRecord(orderId = orderId, contract = contract, action = action, price = limitPrice, qty = qty))
        return LegOrder(orderId = orderId, status = OrderStatus.FILLED)
    }

    override suspend fun cancelOrder(orderId: Int) {
        // No-op in backtest — fills are always immediate
    }
}

data class FillRecord(
    val orderId: Int,
    val contract: OptionContract,
    val action: LegAction,
    val price: Money,
    val qty: Int,
)
