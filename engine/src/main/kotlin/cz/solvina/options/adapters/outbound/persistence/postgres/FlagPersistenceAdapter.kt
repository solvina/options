package cz.solvina.options.adapters.outbound.persistence.postgres

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.FlagPositionEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.FlagPositionRepository
import cz.solvina.options.domain.features.flag.FlagPage
import cz.solvina.options.domain.features.flag.FlagPort
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class FlagPersistenceAdapter(
    private val repository: FlagPositionRepository,
) : FlagPort {

    override suspend fun save(position: FlagPosition): FlagPosition =
        withContext(Dispatchers.IO) { repository.save(position.toEntity()).toDomain() }

    override suspend fun update(position: FlagPosition): FlagPosition =
        withContext(Dispatchers.IO) {
            requireNotNull(position.id) { "Cannot update FlagPosition without id" }
            repository.save(position.toEntity()).toDomain()
        }

    override suspend fun findById(id: UUID): FlagPosition? =
        withContext(Dispatchers.IO) { repository.findById(id).orElse(null)?.toDomain() }

    override suspend fun findOpen(): List<FlagPosition> =
        withContext(Dispatchers.IO) {
            repository.findByStatusOrderByOpenedAtDesc(FlagStatus.OPEN.name).map { it.toDomain() }
        }

    override suspend fun findAll(): List<FlagPosition> =
        withContext(Dispatchers.IO) { repository.findAllByOrderByOpenedAtDesc().map { it.toDomain() } }

    override suspend fun findPage(
        status: FlagStatus?,
        page: Int,
        size: Int,
    ): FlagPage =
        withContext(Dispatchers.IO) {
            val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "openedAt"))
            val result = if (status != null) repository.findByStatus(status.name, pageable) else repository.findAllBy(pageable)
            FlagPage(
                content = result.content.map { it.toDomain() },
                totalElements = result.totalElements,
                totalPages = result.totalPages,
                page = result.number,
                size = result.size,
            )
        }

    override suspend fun countByStatus(status: FlagStatus): Long =
        withContext(Dispatchers.IO) { repository.countByStatus(status.name) }

    override suspend fun findByStatus(status: FlagStatus): List<FlagPosition> =
        withContext(Dispatchers.IO) {
            repository.findByStatusOrderByOpenedAtDesc(status.name).map { it.toDomain() }
        }

    private fun FlagPosition.toEntity(): FlagPositionEntity =
        FlagPositionEntity(
            id = id,
            symbol = symbol.value,
            status = status.name,
            entryOrderId = entryOrderId,
            stopLossOrderId = stopLossOrderId,
            profitTargetOrderId = profitTargetOrderId,
            entryPrice = entryPrice,
            stopLossPrice = stopLossPrice,
            profitTargetPrice = profitTargetPrice,
            shares = shares,
            riskAmount = riskAmount,
            flagpoleHeight = flagpoleHeight,
            flagRetracement = flagRetracement,
            resistanceAtEntry = resistanceAtEntry,
            patternStartedAt = patternStartedAt,
            openedAt = openedAt,
            closedAt = closedAt,
            closeReason = closeReason,
            closePriceActual = closePriceActual,
            realizedPnl = realizedPnl,
        )

    private fun FlagPositionEntity.toDomain(): FlagPosition =
        FlagPosition(
            id = requireNotNull(id) { "Entity must have id after persistence" },
            symbol = Symbol(symbol),
            status = FlagStatus.valueOf(status),
            entryOrderId = entryOrderId,
            stopLossOrderId = stopLossOrderId,
            profitTargetOrderId = profitTargetOrderId,
            entryPrice = entryPrice,
            stopLossPrice = stopLossPrice,
            profitTargetPrice = profitTargetPrice,
            shares = shares,
            riskAmount = riskAmount,
            flagpoleHeight = flagpoleHeight,
            flagRetracement = flagRetracement,
            resistanceAtEntry = resistanceAtEntry,
            patternStartedAt = patternStartedAt,
            openedAt = openedAt,
            closedAt = closedAt,
            closeReason = closeReason,
            closePriceActual = closePriceActual,
            realizedPnl = realizedPnl,
        )
}
