package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.SpreadPositionEntity
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.lang.NonNull
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SpreadPositionEntity s WHERE s.symbol = :symbol")
    fun findBySymbolWithLock(
        @Param("symbol") @NonNull symbol: String,
    ): List<SpreadPositionEntity>
}
