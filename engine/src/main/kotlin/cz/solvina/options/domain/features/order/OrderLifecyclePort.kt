package cz.solvina.options.domain.features.order

import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.Instant

/**
 * Push view of orders known to the broker. Implementations must be fed by the broker's continuous
 * order callbacks; consumers must not turn a missing update into a broker query.
 */
interface OrderLifecyclePort {
    val updates: Flow<BrokerOrderUpdate>

    /** Latest callback-derived state, used to close the subscribe/register race during recovery. */
    fun current(orderId: Int): BrokerOrderUpdate?
}

data class BrokerOrderUpdate(
    val orderId: Int,
    val status: String,
    val filled: BigDecimal,
    val remaining: BigDecimal,
    val averageFillPrice: BigDecimal? = null,
    val rejectionReason: String? = null,
    val receivedAt: Instant,
) {
    val isFilled: Boolean get() = status.equals("filled", ignoreCase = true)
    val isCancelled: Boolean
        get() = status.lowercase() in setOf("cancelled", "inactive", "apicancelled", "rejected")
}
