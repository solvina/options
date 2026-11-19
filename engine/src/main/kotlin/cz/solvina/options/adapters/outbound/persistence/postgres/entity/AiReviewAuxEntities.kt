package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** Append-only attempt log. See v46. */
@Entity
@Table(name = "ai_review_attempt")
class AiReviewAttemptEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "task_id", nullable = false)
    var taskId: UUID = UUID(0, 0),
    @Column(name = "reviewer_id", columnDefinition = "TEXT", nullable = false)
    var reviewerId: String = "",
    @Column(nullable = false)
    var attempt: Int = 0,
    @Column(columnDefinition = "TEXT", nullable = false)
    var event: String = "",
    @Column(name = "failure_kind", columnDefinition = "TEXT")
    var failureKind: String? = null,
    @Column(columnDefinition = "TEXT")
    var message: String? = null,
    @Column(nullable = false)
    var at: Instant = Instant.EPOCH,
)

/** Full prompt text behind a prompt hash. See v46. */
@Entity
@Table(name = "ai_review_prompt")
class AiReviewPromptEntity(
    @Id
    @Column(name = "prompt_hash", columnDefinition = "TEXT")
    var promptHash: String = "",
    @Column(name = "reviewer_id", columnDefinition = "TEXT", nullable = false)
    var reviewerId: String = "",
    @Column(name = "prompt_version", columnDefinition = "TEXT", nullable = false)
    var promptVersion: String = "",
    @Column(columnDefinition = "TEXT", nullable = false)
    var text: String = "",
    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: Instant = Instant.EPOCH,
)

/** Spread-rule configuration behind a rules_version hash. See v46. */
@Entity
@Table(name = "ai_review_rules")
class AiReviewRulesEntity(
    @Id
    @Column(name = "rules_version", columnDefinition = "TEXT")
    var rulesVersion: String = "",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var config: String = "{}",
    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: Instant = Instant.EPOCH,
)

/** Current reviewer state. See v46. */
@Entity
@Table(name = "ai_reviewer_status")
class AiReviewerStatusEntity(
    @Id
    @Column(name = "reviewer_id", columnDefinition = "TEXT")
    var reviewerId: String = "",
    @Column(columnDefinition = "TEXT", nullable = false)
    var state: String = "READY",
    @Column(columnDefinition = "TEXT")
    var message: String? = null,
    @Column(columnDefinition = "TEXT")
    var provider: String? = null,
    @Column(columnDefinition = "TEXT")
    var model: String? = null,
    @Column(name = "reviewer_version", columnDefinition = "TEXT")
    var reviewerVersion: String? = null,
    @Column(name = "last_heartbeat_at")
    var lastHeartbeatAt: Instant? = null,
    @Column(name = "last_result_at")
    var lastResultAt: Instant? = null,
    @Column(name = "last_failure", columnDefinition = "TEXT")
    var lastFailure: String? = null,
    @Column(name = "consecutive_failures", nullable = false)
    var consecutiveFailures: Int = 0,
)
