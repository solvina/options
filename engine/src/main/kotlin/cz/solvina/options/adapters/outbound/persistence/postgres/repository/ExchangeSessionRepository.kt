package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.ExchangeSessionEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ExchangeSessionRepository : JpaRepository<ExchangeSessionEntity, String>
