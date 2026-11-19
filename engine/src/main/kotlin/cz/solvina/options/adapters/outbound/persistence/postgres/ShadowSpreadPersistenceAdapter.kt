package cz.solvina.options.adapters.outbound.persistence.postgres

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.ShadowSpreadEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.ShadowSpreadMarkEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.ShadowSpreadMarkRepository
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

@Component
class ShadowSpreadPersistenceAdapter(
    private val repository: ShadowSpreadRepository,
    private val marks: ShadowSpreadMarkRepository,
) : ShadowSpreadPort {
    override suspend fun findOpen(): List<ShadowSpread> =
        withContext(Dispatchers.IO) { repository.findByStatusAndCountedTrue(ShadowStatus.OPEN.name).map { it.toDomain() } }

    override suspend fun findAll(): List<ShadowSpread> =
        withContext(Dispatchers.IO) { repository.findByCountedTrue().map { it.toDomain() } }

    override suspend fun save(shadow: ShadowSpread): ShadowSpread =
        withContext(Dispatchers.IO) { repository.save(shadow.toEntity()).toDomain() }

    override suspend fun saveMark(
        shadowId: UUID,
        valuedAt: Instant,
        value: BigDecimal?,
        underlying: BigDecimal?,
        status: ShadowStatus,
    ) {
        withContext(Dispatchers.IO) {
            marks.save(
                ShadowSpreadMarkEntity(
                    shadowSpreadId = shadowId,
                    valuedAt = valuedAt,
                    value = value,
                    underlying = underlying,
                    status = status.name,
                ),
            )
        }
    }

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
            score = score?.toBigDecimal(),
            decisionRank = decisionRank,
            firstSeenAt = firstSeenAt,
            selected = selected,
            flags = flags.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(","),
            aiTaskId = aiTaskId,
            aiVerdict = aiVerdict,
            aiConfidence = aiConfidence?.let { BigDecimal(it).setScale(4, RoundingMode.HALF_UP) },
            aiSelected = aiSelected,
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
            score = score?.toDouble(),
            decisionRank = decisionRank,
            firstSeenAt = firstSeenAt,
            selected = selected,
            flags = flags?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
            aiTaskId = aiTaskId,
            aiVerdict = aiVerdict,
            aiConfidence = aiConfidence?.toDouble(),
            aiSelected = aiSelected,
        )
}
