package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.StockPositionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface StockPositionRepository : JpaRepository<StockPositionEntity, UUID> {
    fun findByStatusIn(statuses: Collection<String>): List<StockPositionEntity>

    fun findByStrategyIdAndSymbolAndStatusIn(
        strategyId: String,
        symbol: String,
        statuses: Collection<String>,
    ): List<StockPositionEntity>
}
