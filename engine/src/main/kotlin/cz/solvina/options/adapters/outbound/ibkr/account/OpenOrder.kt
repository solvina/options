package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.Decimal

data class OpenOrder(
    val orderId: Int,
    val symbol: String,
    val action: String,
    val orderType: String,
    val limitPrice: Double?,
    val status: String,
    val filled: Decimal = Decimal.ZERO,
    val remaining: Decimal = Decimal.ZERO,
    val avgFillPrice: Double = 0.0,
    val permId: Int = 0,
    val parentId: Int = 0,
    val lastFillPrice: Double = 0.0,
    val whyHeld: String? = null,
    val mktCapPrice: Double = 0.0,
)
