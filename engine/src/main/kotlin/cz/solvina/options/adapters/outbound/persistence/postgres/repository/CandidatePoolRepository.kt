package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.CandidatePoolEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface CandidatePoolRepository : JpaRepository<CandidatePoolEntity, UUID> {
    fun findByPoolDate(poolDate: LocalDate): List<CandidatePoolEntity>

    fun existsByPoolDateAndSymbol(
        poolDate: LocalDate,
        symbol: String,
    ): Boolean

    fun deleteByPoolDateAndSymbol(
        poolDate: LocalDate,
        symbol: String,
    ): Long

    fun deleteByPoolDate(poolDate: LocalDate): Long

    fun deleteByPoolDateBefore(poolDate: LocalDate): Long
}
