package cz.solvina.options.domain.features.account

import java.math.BigDecimal

class AccountOrderNotFoundException(
    val orderId: Int,
) : RuntimeException("Open order $orderId was not found")

class AccountOrderNotCancellableException(
    val orderId: Int,
    message: String,
) : RuntimeException(message)

interface AccountTradingPort {
    suspend fun getOpenOrders(): List<AccountOpenOrder>

    suspend fun cancelOrder(orderId: Int)

    suspend fun closePosition(
        conId: Int,
        quantity: BigDecimal,
    )
}
