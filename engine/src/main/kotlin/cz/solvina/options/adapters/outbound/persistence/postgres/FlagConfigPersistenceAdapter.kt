package cz.solvina.options.adapters.outbound.persistence.postgres

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.FlagTradingConfigEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.repository.FlagTradingConfigRepository
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfigPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component

@Component
class FlagConfigPersistenceAdapter(
    private val repository: FlagTradingConfigRepository,
) : FlagTradingConfigPort {

    override suspend fun get(): FlagTradingConfig =
        withContext(Dispatchers.IO) {
            repository.findById(1L).orElseGet {
                // Seed the default row if not present (should already be seeded by Liquibase)
                repository.save(FlagTradingConfigEntity())
            }.toDomain()
        }

    override suspend fun update(config: FlagTradingConfig): FlagTradingConfig =
        withContext(Dispatchers.IO) {
            repository.save(config.toEntity()).toDomain()
        }

    private fun FlagTradingConfig.toEntity(): FlagTradingConfigEntity =
        FlagTradingConfigEntity(
            id = id,
            riskPerTrade = riskPerTrade,
            maxOpenPositions = maxOpenPositions,
            enabled = enabled,
            entryBlockMinutesBeforeClose = entryBlockMinutesBeforeClose,
            eodLiqMinutesBeforeClose = eodLiqMinutesBeforeClose,
        )

    private fun FlagTradingConfigEntity.toDomain(): FlagTradingConfig =
        FlagTradingConfig(
            id = id,
            riskPerTrade = riskPerTrade,
            maxOpenPositions = maxOpenPositions,
            enabled = enabled,
            entryBlockMinutesBeforeClose = entryBlockMinutesBeforeClose,
            eodLiqMinutesBeforeClose = eodLiqMinutesBeforeClose,
        )
}
