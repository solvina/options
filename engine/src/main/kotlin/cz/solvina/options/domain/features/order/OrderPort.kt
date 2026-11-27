package cz.solvina.options.domain.features.order

import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import java.math.BigDecimal

interface OrderPort {
    suspend fun placeAndAwaitFill(
        contract: OptionContract,
        action: LegAction,
        limitPrice: Money,
        qty: Int,
    ): LegOrder

    suspend fun placeMarketOrder(
        contract: OptionContract,
        action: LegAction,
        qty: Int,
    ): LegOrder

    suspend fun cancelOrder(orderId: Int)

    /**
     * Consume (one-shot) the broker-reported average fill price of [orderId], per share. Null when the
     * broker reported none. Lets closes record what was actually paid instead of a pre-order mid.
     */
    fun consumeFillPrice(orderId: Int): BigDecimal? = null

    /** Total commission IBKR reported for [orderId], or null if none arrived in time. */
    suspend fun awaitCommission(orderId: Int): BigDecimal? = null
}
