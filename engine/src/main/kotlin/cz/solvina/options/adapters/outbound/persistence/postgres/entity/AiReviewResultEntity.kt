package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Row backing [cz.solvina.options.domain.features.aireview.AiReviewResult]. See v46. */
@Entity
@Table(name = "ai_review_result")
class AiReviewResultEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "task_id", nullable = false)
    var taskId: UUID = UUID(0, 0),
    @Column(name = "reviewer_id", columnDefinition = "TEXT", nullable = false)
    var reviewerId: String = "",
    @Column(columnDefinition = "TEXT", nullable = false)
    var status: String = "PENDING",
    @Column(nullable = false)
    var attempts: Int = 0,
    @Column(name = "lease_token", columnDefinition = "TEXT")
    var leaseToken: String? = null,
    @Column(name = "lease_expires_at")
    var leaseExpiresAt: Instant? = null,
    @Column(name = "claimed_at")
    var claimedAt: Instant? = null,
    @Column(name = "contract_version", columnDefinition = "TEXT")
    var contractVersion: String? = null,
    @Column(columnDefinition = "TEXT")
    var verdict: String? = null,
    @Column
    var confidence: BigDecimal? = null,
    @Column(columnDefinition = "TEXT")
    var reason: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var risks: String? = null,
    @Column(name = "news_summary", columnDefinition = "TEXT")
    var newsSummary: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var sources: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var provenance: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var extras: String? = null,
    @Column(name = "preferred_strategy", columnDefinition = "TEXT")
    var preferredStrategy: String? = null,
    @Column(name = "raw_response", columnDefinition = "TEXT")
    var rawResponse: String? = null,
    @Column(name = "prompt_hash", columnDefinition = "TEXT")
    var promptHash: String? = null,
    @Column(name = "prompt_unregistered", nullable = false)
    var promptUnregistered: Boolean = false,
    @Column(columnDefinition = "TEXT")
    var model: String? = null,
    @Column(nullable = false)
    var late: Boolean = false,
    @Column(name = "completed_at")
    var completedAt: Instant? = null,
    @Column(name = "last_failure_kind", columnDefinition = "TEXT")
    var lastFailureKind: String? = null,
    @Column(name = "last_failure", columnDefinition = "TEXT")
    var lastFailure: String? = null,
)
