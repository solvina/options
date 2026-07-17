package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.BacktestConfigEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BacktestConfigRepository : JpaRepository<BacktestConfigEntity, UUID> {
    fun findAllByOrderByCreatedAtDesc(): List<BacktestConfigEntity>

    fun findByName(name: String): BacktestConfigEntity?
}
