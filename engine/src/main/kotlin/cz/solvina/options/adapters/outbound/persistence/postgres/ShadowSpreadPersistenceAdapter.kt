package cz.solvina.options.adapters.outbound.persistence.postgres

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.ShadowSpreadEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.ShadowSpreadRepository
import cz.solvina.options.domain.features.shadow.ShadowSpread
import cz.solvina.options.domain.features.shadow.ShadowSpreadPort
import cz.solvina.options.domain.features.shadow.ShadowStatus
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component

@Component
class ShadowSpreadPersistenceAdapter(
    private val repository: ShadowSpreadRepository,
) : ShadowSpreadPort {
    override suspend fun findOpen(): List<ShadowSpread> =
        withContext(Dispatchers.IO) { repository.findByStatus(ShadowStatus.OPEN.name).map { it.toDomain() } }

    override suspend fun findAll(): List<ShadowSpread> = withContext(Dispatchers.IO) { repository.findAll().map { it.toDomain() } }

    override suspend fun save(shadow: ShadowSpread): ShadowSpread =
        withContext(Dispatchers.IO) { repository.save(shadow.toEntity()).toDomain() }

    private fun ShadowSpread.toEntity() =
        ShadowSpreadEntity(
            id = id,
            strategyId = strategyId.name,
            symbol = symbol.value,
            optionType = optionType.name,
            expiry = expiry,
            soldStrike = soldStrike,
            boughtStrike = boughtStrike,
            openedAt = openedAt,
            sourceOutcome = sourceOutcome,
            midCredit = midCredit,
            floorCredit = floorCredit,
            naturalCredit = naturalCredit,
            underlyingAtEntry = underlyingAtEntry,
            ivRankAtEntry = ivRankAtEntry?.toBigDecimal(),
            status = status.name,
            closeReason = closeReason,
            closedAt = closedAt,
            exitValue = exitValue,
            lastValue = lastValue,
            lastUnderlying = lastUnderlying,
            lastValuedAt = lastValuedAt,
            backfilled = backfilled,
        )

    private fun ShadowSpreadEntity.toDomain() =
        ShadowSpread(
            id = id,
            strategyId = StrategyId.valueOf(strategyId),
            symbol = Symbol(symbol),
            optionType = OptionType.valueOf(optionType),
            expiry = expiry,
            soldStrike = soldStrike,
            boughtStrike = boughtStrike,
            openedAt = openedAt,
            sourceOutcome = sourceOutcome,
            midCredit = midCredit,
            floorCredit = floorCredit,
            naturalCredit = naturalCredit,
            underlyingAtEntry = underlyingAtEntry,
            ivRankAtEntry = ivRankAtEntry?.toDouble(),
            status = ShadowStatus.valueOf(status),
            closeReason = closeReason,
            closedAt = closedAt,
            exitValue = exitValue,
            lastValue = lastValue,
            lastUnderlying = lastUnderlying,
            lastValuedAt = lastValuedAt,
            backfilled = backfilled,
        )
}
