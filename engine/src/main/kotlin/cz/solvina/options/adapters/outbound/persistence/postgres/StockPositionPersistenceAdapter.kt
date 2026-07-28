package cz.solvina.options.adapters.outbound.persistence.postgres

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.StockPositionEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.StockPositionRepository
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.strategy.live.StockPosition
import cz.solvina.options.domain.features.strategy.live.StockPositionPort
import cz.solvina.options.domain.features.strategy.live.StockPositionStatus
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class StockPositionPersistenceAdapter(
    private val repository: StockPositionRepository,
) : StockPositionPort {
    private val liveStatuses = StockPositionStatus.entries.filter { it.isLive }.map { it.name }

    override suspend fun save(position: StockPosition): StockPosition =
        withContext(Dispatchers.IO) {
            repository.save(position.toEntity()).toDomain()
        }

    override suspend fun findById(id: UUID): StockPosition? =
        withContext(Dispatchers.IO) {
            repository.findById(id).orElse(null)?.toDomain()
        }

    override suspend fun findLive(): List<StockPosition> =
        withContext(Dispatchers.IO) {
            repository.findByStatusIn(liveStatuses).map { it.toDomain() }
        }

    override suspend fun findAll(): List<StockPosition> =
        withContext(Dispatchers.IO) {
            repository.findAll().map { it.toDomain() }
        }

    override suspend fun findLiveFor(
        strategyId: String,
        symbol: Symbol,
    ): StockPosition? =
        withContext(Dispatchers.IO) {
            repository
                .findByStrategyIdAndSymbolAndStatusIn(strategyId, symbol.value, liveStatuses)
                .firstOrNull()
                ?.toDomain()
        }

    private fun StockPosition.toEntity() =
        StockPositionEntity(
            id = id,
            strategyId = strategyId,
            assignmentId = assignmentId,
            paramsJson = paramsJson,
            symbol = symbol.value,
            timeframe = timeframe.label,
            status = status.name,
            entryOrderId = entryOrderId,
            stopOrderId = stopOrderId,
            targetOrderId = targetOrderId,
            closeOrderId = closeOrderId,
            signalPrice = signalPrice,
            limitPrice = limitPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            shares = shares,
            riskAmount = riskAmount,
            actualEntryPrice = actualEntryPrice,
            closePrice = closePrice,
            realizedPnl = realizedPnl,
            closeReason = closeReason,
            highestPriceSeen = highestPriceSeen,
            lowestPriceSeen = lowestPriceSeen,
            signalledAt = signalledAt,
            openedAt = openedAt,
            closedAt = closedAt,
        )

    private fun StockPositionEntity.toDomain() =
        StockPosition(
            id = id,
            strategyId = strategyId,
            assignmentId = assignmentId,
            paramsJson = paramsJson,
            symbol = Symbol(symbol),
            timeframe = Timeframe.fromLabel(timeframe),
            status = StockPositionStatus.valueOf(status),
            entryOrderId = entryOrderId,
            stopOrderId = stopOrderId,
            targetOrderId = targetOrderId,
            closeOrderId = closeOrderId,
            signalPrice = signalPrice,
            limitPrice = limitPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            shares = shares,
            riskAmount = riskAmount,
            actualEntryPrice = actualEntryPrice,
            closePrice = closePrice,
            realizedPnl = realizedPnl,
            closeReason = closeReason,
            highestPriceSeen = highestPriceSeen,
            lowestPriceSeen = lowestPriceSeen,
            signalledAt = signalledAt,
            openedAt = openedAt,
            closedAt = closedAt,
        )
}
