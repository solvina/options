package cz.solvina.options.domain.features.order

import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract

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
}
