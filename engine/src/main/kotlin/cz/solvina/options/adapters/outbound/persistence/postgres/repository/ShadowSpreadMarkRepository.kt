package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.ShadowSpreadMarkEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ShadowSpreadMarkRepository : JpaRepository<ShadowSpreadMarkEntity, Long>
