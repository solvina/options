package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.StrategyAssignmentEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StrategyAssignmentRepository : JpaRepository<StrategyAssignmentEntity, UUID> {
    fun findByEnabledTrue(): List<StrategyAssignmentEntity>

    fun findByStrategyId(strategyId: String): List<StrategyAssignmentEntity>

    fun findByStrategyIdAndSymbolAndTimeframe(
        strategyId: String,
        symbol: String,
        timeframe: String,
    ): StrategyAssignmentEntity?
}
