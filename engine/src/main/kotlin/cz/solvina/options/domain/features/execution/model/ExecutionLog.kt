package cz.solvina.options.domain.features.execution.model

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ExecutionLog(
    val id: UUID? = null,
    val symbol: Symbol,
    val soldStrike: BigDecimal,
    val boughtStrike: BigDecimal,
    val expiryDate: LocalDate,
    val quantity: Int,
    val orderId: Int? = null,
    val eventType: ExecutionEventType,
    val eventStatus: String,
    val targetCredit: BigDecimal? = null,
    val ivRank: BigDecimal? = null,
    val underlyingPrice: BigDecimal? = null,
    val reason: String? = null,
    val spreadId: UUID? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant? = null,
)

enum class ExecutionEventType {
    CANDIDATE,
    SUBMITTED,
    LADDERED,
    FILLED,
    REJECTED,
    CANCELLED,
    TIMED_OUT,
    ABORTED,
    FLOOR_REACHED,
    DRIFT_ABORTED,
    ORDER_REJECTED,
}
