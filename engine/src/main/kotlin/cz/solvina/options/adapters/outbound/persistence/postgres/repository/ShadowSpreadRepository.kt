package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.ShadowSpreadEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ShadowSpreadRepository : JpaRepository<ShadowSpreadEntity, UUID> {
    fun findByStatus(status: String): List<ShadowSpreadEntity>
}
