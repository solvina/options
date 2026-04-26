package cz.solvina.options.domain.features.account

import cz.solvina.options.domain.models.Money

data class AccountDetail(
    val totalCapital: Money? = null,
    val availableFunds: Money? = null,
    val unrealizedPnL: Money? = null,
    val excessLiquidity: Money? = null,
)
