package cz.solvina.options.domain.features.order

data class LegOrder(
    val orderId: Int,
    val status: OrderStatus,
)
