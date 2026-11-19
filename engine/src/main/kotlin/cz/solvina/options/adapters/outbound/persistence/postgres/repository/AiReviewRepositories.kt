package cz.solvina.options.adapters.outbound.persistence.postgres.repository

import cz.solvina.options.adapters.outbound.persistence.postgres.entity.AiReviewAttemptEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.AiReviewPromptEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.AiReviewResultEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.AiReviewRulesEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.AiReviewTaskEntity
import cz.solvina.options.adapters.outbound.persistence.postgres.entity.AiReviewerStatusEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface AiReviewTaskRepository : JpaRepository<AiReviewTaskEntity, UUID> {
    fun existsByTradingDateAndStrategyIdAndSymbol(
        tradingDate: LocalDate,
        strategyId: String,
        symbol: String,
    ): Boolean

    fun countByTradingDate(tradingDate: LocalDate): Long

    // Separate derived queries instead of one "(:p IS NULL OR …)" JPQL: Postgres can't type a bare
    // null parameter, so the optional-filter form fails at runtime.
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<AiReviewTaskEntity>

    fun findByTradingDateGreaterThanEqualOrderByCreatedAtDesc(
        from: LocalDate,
        pageable: Pageable,
    ): List<AiReviewTaskEntity>

    fun findBySymbolOrderByCreatedAtDesc(
        symbol: String,
        pageable: Pageable,
    ): List<AiReviewTaskEntity>

    fun findBySymbolAndTradingDateGreaterThanEqualOrderByCreatedAtDesc(
        symbol: String,
        from: LocalDate,
        pageable: Pageable,
    ): List<AiReviewTaskEntity>
}

interface AiReviewResultRepository : JpaRepository<AiReviewResultEntity, UUID> {
    fun findByTaskIdAndReviewerId(
        taskId: UUID,
        reviewerId: String,
    ): AiReviewResultEntity?

    fun findByTaskIdIn(taskIds: Collection<UUID>): List<AiReviewResultEntity>

    fun findByStatusAndLeaseExpiresAtBefore(
        status: String,
        now: Instant,
    ): List<AiReviewResultEntity>

    fun findByStatusAndAttemptsGreaterThanEqual(
        status: String,
        attempts: Int,
    ): List<AiReviewResultEntity>

    @Query(
        """
        SELECT r FROM AiReviewResultEntity r, AiReviewTaskEntity t
        WHERE t.id = r.taskId AND r.status IN ('PENDING', 'IN_PROGRESS') AND t.deadlineAt < :now
        """,
    )
    fun findUnfinishedPastDeadline(
        @Param("now") now: Instant,
    ): List<AiReviewResultEntity>

    /**
     * Row-locks the next claimable result for a reviewer. SKIP LOCKED lets two reviewer instances
     * claim concurrently without ever getting the same task; must run inside a transaction.
     */
    @Query(
        value = """
        SELECT r.* FROM ai_review_result r JOIN ai_review_task t ON t.id = r.task_id
        WHERE r.reviewer_id = :reviewerId
          AND t.status = 'OPEN'
          AND t.snapshot_version IN (:versions)
          AND t.deadline_at > :now
          AND r.attempts < :maxAttempts
          AND (r.status = 'PENDING' OR (r.status = 'IN_PROGRESS' AND r.lease_expires_at < :now))
        ORDER BY t.deadline_at, t.created_at
        LIMIT 1
        FOR UPDATE OF r SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun lockNextClaimable(
        @Param("reviewerId") reviewerId: String,
        @Param("versions") versions: Collection<Int>,
        @Param("now") now: Instant,
        @Param("maxAttempts") maxAttempts: Int,
    ): AiReviewResultEntity?
}

interface AiReviewAttemptRepository : JpaRepository<AiReviewAttemptEntity, Long>

interface AiReviewPromptRepository : JpaRepository<AiReviewPromptEntity, String>

interface AiReviewRulesRepository : JpaRepository<AiReviewRulesEntity, String>

interface AiReviewerStatusRepository : JpaRepository<AiReviewerStatusEntity, String>
