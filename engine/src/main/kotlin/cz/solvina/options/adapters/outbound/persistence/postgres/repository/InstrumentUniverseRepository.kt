package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.InstrumentUniverseEntity
import org.springframework.data.jpa.repository.JpaRepository

interface InstrumentUniverseRepository : JpaRepository<InstrumentUniverseEntity, String> {
    fun findByEnabledTrue(): List<InstrumentUniverseEntity>
}
