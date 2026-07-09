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

    private val sortableFields = setOf("openedAt", "closedAt", "realizedPnl", "rMultiple", "timeInTradeSeconds", "symbol", "entryPrice")

    override suspend fun findPage(
        status: FlagStatus?,
        page: Int,
        size: Int,
        sort: String,
        sortDir: String,
    ): FlagPage =
        withContext(Dispatchers.IO) {
            val direction = if (sortDir.uppercase() == "ASC") Sort.Direction.ASC else Sort.Direction.DESC
            val sortField = if (sort in sortableFields) sort else "openedAt"
            val pageable = PageRequest.of(page, size, Sort.by(direction, sortField))
            val result = if (status != null) repository.findByStatus(status.name, pageable) else repository.findAllBy(pageable)
            FlagPage(
                content = result.content.map { it.toDomain() },
                totalElements = result.totalElements,
                totalPages = result.totalPages,
                page = result.number,
                size = result.size,
            )
        }

    override suspend fun countByStatus(status: FlagStatus): Long = withContext(Dispatchers.IO) { repository.countByStatus(status.name) }

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
            trailAmount = trailAmount,
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
            strategyName = strategyName,
            actualEntryPrice = actualEntryPrice,
            highestPriceSeen = highestPriceSeen,
            lowestPriceSeen = lowestPriceSeen,
            maxFavorableExcursion = maxFavorableExcursion,
            maxAdverseExcursion = maxAdverseExcursion,
            flagBarCount = flagBarCount,
            flagpoleBarCount = flagpoleBarCount,
            flagpoleAvgVolume = flagpoleAvgVolume,
            flagAvgVolume = flagAvgVolume,
            channelSlope = channelSlope,
            marketSession = marketSession,
            minutesToClose = minutesToClose,
            entrySlippage = entrySlippage,
            rMultiple = rMultiple,
            timeInTradeSeconds = timeInTradeSeconds,
            atrAtEntry = atrAtEntry,
            volumeMaAtEntry = volumeMaAtEntry,
            flagpoleVolumeRatio = flagpoleVolumeRatio,
            vwapAtEntry = vwapAtEntry,
            dayOpenPrice = dayOpenPrice,
            breakoutType = breakoutType,
            stopDistancePct = stopDistancePct,
            mfeR = mfeR,
            maeR = maeR,
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
            trailAmount = trailAmount,
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
            strategyName = strategyName,
            actualEntryPrice = actualEntryPrice,
            highestPriceSeen = highestPriceSeen,
            lowestPriceSeen = lowestPriceSeen,
            maxFavorableExcursion = maxFavorableExcursion,
            maxAdverseExcursion = maxAdverseExcursion,
            flagBarCount = flagBarCount,
            flagpoleBarCount = flagpoleBarCount,
            flagpoleAvgVolume = flagpoleAvgVolume,
            flagAvgVolume = flagAvgVolume,
            channelSlope = channelSlope,
            marketSession = marketSession,
            minutesToClose = minutesToClose,
            entrySlippage = entrySlippage,
            rMultiple = rMultiple,
            timeInTradeSeconds = timeInTradeSeconds,
            atrAtEntry = atrAtEntry,
            volumeMaAtEntry = volumeMaAtEntry,
            flagpoleVolumeRatio = flagpoleVolumeRatio,
            vwapAtEntry = vwapAtEntry,
            dayOpenPrice = dayOpenPrice,
            breakoutType = breakoutType,
            stopDistancePct = stopDistancePct,
            mfeR = mfeR,
            maeR = maeR,
        )
}
