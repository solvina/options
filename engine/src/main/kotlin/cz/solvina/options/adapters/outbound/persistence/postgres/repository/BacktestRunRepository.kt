package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.BacktestRunEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BacktestRunRepository : JpaRepository<BacktestRunEntity, UUID> {
    fun findAllByOrderByCreatedAtDesc(): List<BacktestRunEntity>
}
