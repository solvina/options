package cz.solvina.options.domain.features.execution

import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.execution.model.TradeExecutionResult
import cz.solvina.options.domain.models.Symbol
import java.time.Duration

interface TradeExecutionPort {
    suspend fun execute(request: TradeExecutionRequest): TradeExecutionResult

    fun isInFlight(symbol: Symbol): Boolean

    fun isCoolingDown(symbol: Symbol): Boolean

    fun blockEntry(
        symbol: Symbol,
        duration: Duration,
    )
}
