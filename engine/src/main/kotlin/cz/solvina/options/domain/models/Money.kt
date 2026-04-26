package cz.solvina.options.domain.models

import java.math.BigDecimal

data class Money(
    val amount: BigDecimal,
    val currency: String = "USD",
) {
    operator fun plus(other: Money): Money = copy(amount = amount + other.amount)

    operator fun minus(other: Money): Money = copy(amount = amount - other.amount)

    operator fun compareTo(other: Money): Int = amount.compareTo(other.amount)
}
