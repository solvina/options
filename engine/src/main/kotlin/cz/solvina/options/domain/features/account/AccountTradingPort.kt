package cz.solvina.options.domain.features.account

import java.math.BigDecimal

interface AccountTradingPort {
    suspend fun getOpenOrders(): List<AccountOpenOrder>

    suspend fun cancelOrder(orderId: Int)

    suspend fun closePosition(
        conId: Int,
        quantity: BigDecimal,
    )
}
