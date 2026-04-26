package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.SpreadPositionEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpreadPositionRepository : JpaRepository<SpreadPositionEntity, UUID> {
    fun findByStatus(status: String): List<SpreadPositionEntity>

    fun countByStatus(status: String): Long
}
