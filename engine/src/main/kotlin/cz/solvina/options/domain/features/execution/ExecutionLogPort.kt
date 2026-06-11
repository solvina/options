package cz.solvina.options.domain.features.execution

import cz.solvina.options.domain.features.execution.model.ExecutionEventType
import cz.solvina.options.domain.features.execution.model.ExecutionLog
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

interface ExecutionLogPort {
    suspend fun log(executionLog: ExecutionLog): ExecutionLog

    suspend fun logEvent(
        symbol: Symbol,
        soldStrike: BigDecimal,
        boughtStrike: BigDecimal,
        expiryDate: LocalDate,
        quantity: Int,
        eventType: ExecutionEventType,
        eventStatus: String,
        orderId: Int? = null,
        targetCredit: BigDecimal? = null,
        ivRank: BigDecimal? = null,
        underlyingPrice: BigDecimal? = null,
        reason: String? = null,
        spreadId: UUID? = null,
    ): ExecutionLog

    suspend fun findBySpreadId(spreadId: UUID): List<ExecutionLog>

    suspend fun findBySymbol(symbol: Symbol): List<ExecutionLog>

    suspend fun findAll(): List<ExecutionLog>
}
