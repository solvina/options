package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.FlagTradingConfigEntity
import org.springframework.data.jpa.repository.JpaRepository

interface FlagTradingConfigRepository : JpaRepository<FlagTradingConfigEntity, Long>
