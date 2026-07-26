package cz.solvina.options.domain.features.order

enum class OrderStatus {
    PENDING,
    FILLED,
    CANCELLED,
    REJECTED,
    INACTIVE,
    API_CANCELLED,
    ;

    val isTerminal: Boolean get() = this != PENDING
    val isNonFilledTerminal: Boolean get() = isTerminal && this != FILLED

    companion object {
        fun fromBrokerStatus(status: String): OrderStatus =
            when (status.trim().lowercase()) {
                "filled" -> FILLED
                "cancelled", "canceled" -> CANCELLED
                "rejected" -> REJECTED
                "inactive" -> INACTIVE
                "apicancelled", "apicanceled" -> API_CANCELLED
                else -> PENDING
            }
    }
}
