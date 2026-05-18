package cz.solvina.options.adapters.outbound.ibkr.account

data class OpenOrder(
    val orderId: Int,
    val symbol: String,
    val action: String,
    val orderType: String,
    val limitPrice: Double?,
    val status: String,
)
