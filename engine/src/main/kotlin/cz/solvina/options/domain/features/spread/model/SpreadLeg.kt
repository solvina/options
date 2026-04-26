package cz.solvina.options.domain.features.spread.model

import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract

data class SpreadLeg(
    val contract: OptionContract,
    val action: LegAction,
    val premium: Money,
    val orderId: Int,
)
