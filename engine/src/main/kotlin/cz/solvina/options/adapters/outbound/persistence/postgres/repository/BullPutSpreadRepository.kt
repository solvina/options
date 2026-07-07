package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.BullPutSpreadEntity
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.lang.NonNull
import java.time.Instant
import java.util.UUID

interface BullPutSpreadRepository : JpaRepository<BullPutSpreadEntity, UUID> {
    fun findByStatusOrderByOpenedAtDesc(status: String): List<BullPutSpreadEntity>

    fun findAllByOrderByOpenedAtDesc(): List<BullPutSpreadEntity>

    fun findByStatus(
        status: String,
        pageable: Pageable,
    ): Page<BullPutSpreadEntity>

    fun findAllBy(pageable: Pageable): Page<BullPutSpreadEntity>

    fun countByStatus(status: String): Long

    fun countByOpenedAtGreaterThanEqualAndStatusNotIn(
        openedAt: Instant,
        statuses: Collection<String>,
    ): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BullPutSpreadEntity s WHERE s.symbol = :symbol")
    fun findBySymbolWithLock(
        @Param("symbol") @NonNull symbol: String,
    ): List<BullPutSpreadEntity>
}
