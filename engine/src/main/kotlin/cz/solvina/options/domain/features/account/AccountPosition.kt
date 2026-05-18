package cz.solvina.options.domain.features.account

import java.math.BigDecimal
import java.time.LocalDate

data class AccountPosition(
    val account: String,
    val symbol: String,
    val secType: String,
    val currency: String,
    val expiry: LocalDate?,
    val strike: BigDecimal?,
    val optionRight: String?,
    val quantity: BigDecimal,
    val avgCost: BigDecimal,
    val conId: Int = 0,
    val unrealizedPnL: Double? = null,
)
