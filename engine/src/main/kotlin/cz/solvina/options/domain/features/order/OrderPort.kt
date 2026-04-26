package cz.solvina.options.domain.features.order

import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract

interface OrderPort {
    /**
     * Places a limit order for a single leg and waits for fill, chasing price if needed.
     * Returns the leg order with final status (FILLED or CANCELLED).
     */
    suspend fun placeAndAwaitFill(
        contract: OptionContract,
        action: LegAction,
        limitPrice: Money,
        qty: Int,
    ): LegOrder

    suspend fun cancelOrder(orderId: Int)
}
