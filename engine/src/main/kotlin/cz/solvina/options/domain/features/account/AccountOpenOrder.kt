package cz.solvina.options.domain.features.account

import java.math.BigDecimal

data class AccountOpenOrder(
    val orderId: Int,
    val symbol: String,
    val action: String,
    val orderType: String,
    val status: String,
    val limitPrice: BigDecimal?,
)
