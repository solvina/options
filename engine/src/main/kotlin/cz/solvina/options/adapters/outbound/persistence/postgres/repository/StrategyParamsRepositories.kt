package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.StrategyDefaultParamsEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.StrategySymbolParamsEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StrategyDefaultParamsRepository : JpaRepository<StrategyDefaultParamsEntity, String>

interface StrategySymbolParamsRepository : JpaRepository<StrategySymbolParamsEntity, UUID> {
    fun findByStrategyId(strategyId: String): List<StrategySymbolParamsEntity>

    fun findByStrategyIdAndSymbolAndTimeframe(
        strategyId: String,
        symbol: String,
        timeframe: String,
    ): StrategySymbolParamsEntity?
}
