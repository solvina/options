package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.FlagPositionEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface FlagPositionRepository : JpaRepository<FlagPositionEntity, UUID> {
    fun findByStatusOrderByOpenedAtDesc(status: String): List<FlagPositionEntity>

    fun findAllByOrderByOpenedAtDesc(): List<FlagPositionEntity>

    fun findByStatus(
        status: String,
        pageable: Pageable,
    ): Page<FlagPositionEntity>

    fun findAllBy(pageable: Pageable): Page<FlagPositionEntity>

    fun countByStatus(status: String): Long
}
