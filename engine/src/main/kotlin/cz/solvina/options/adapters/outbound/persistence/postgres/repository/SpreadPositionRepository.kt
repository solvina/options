package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.SpreadPositionEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpreadPositionRepository : JpaRepository<SpreadPositionEntity, UUID> {
    fun findByStatusOrderByOpenedAtDesc(status: String): List<SpreadPositionEntity>

    fun findAllByOrderByOpenedAtDesc(): List<SpreadPositionEntity>

    fun findByStatus(
        status: String,
        pageable: Pageable,
    ): Page<SpreadPositionEntity>

    fun findAllBy(pageable: Pageable): Page<SpreadPositionEntity>

    fun countByStatus(status: String): Long
}
