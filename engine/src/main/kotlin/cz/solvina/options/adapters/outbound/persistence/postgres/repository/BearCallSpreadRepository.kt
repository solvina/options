package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.BearCallSpreadEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.lang.NonNull
import java.util.UUID

interface BearCallSpreadRepository : JpaRepository<BearCallSpreadEntity, UUID> {
    fun findByStatusOrderByOpenedAtDesc(status: String): List<BearCallSpreadEntity>

    fun findAllByOrderByOpenedAtDesc(): List<BearCallSpreadEntity>

    fun countByStatus(status: String): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BearCallSpreadEntity s WHERE s.symbol = :symbol")
    fun findBySymbolWithLock(
        @Param("symbol") @NonNull symbol: String,
    ): List<BearCallSpreadEntity>
}
