package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.OptionParamsCacheEntity
import org.springframework.data.jpa.repository.JpaRepository

interface OptionParamsCacheRepository : JpaRepository<OptionParamsCacheEntity, String>
