package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.IvRankCacheEntity
import org.springframework.data.jpa.repository.JpaRepository

interface IvRankCacheRepository : JpaRepository<IvRankCacheEntity, String>
