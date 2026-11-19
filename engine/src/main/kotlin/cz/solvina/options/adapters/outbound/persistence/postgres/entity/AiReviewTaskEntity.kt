package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Row backing [cz.solvina.options.domain.features.aireview.AiReviewTask]. See v46. */
@Entity
@Table(name = "ai_review_task")
class AiReviewTaskEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "trading_date", nullable = false)
    var tradingDate: LocalDate = LocalDate.MIN,
    @Column(name = "strategy_id", columnDefinition = "TEXT", nullable = false)
    var strategyId: String = "",
    @Column(columnDefinition = "TEXT", nullable = false)
    var symbol: String = "",
    @Column(nullable = false)
    var expiry: LocalDate = LocalDate.MIN,
    @Column(name = "short_strike", nullable = false)
    var shortStrike: BigDecimal = BigDecimal.ZERO,
    @Column(name = "long_strike", nullable = false)
    var longStrike: BigDecimal = BigDecimal.ZERO,
    @Column(name = "deadline_at")
    var deadlineAt: Instant? = null,
    @Column(columnDefinition = "TEXT", nullable = false)
    var status: String = "OPEN",
    @Column(name = "skip_reason", columnDefinition = "TEXT")
    var skipReason: String? = null,
    @Column(name = "snapshot_version", nullable = false)
    var snapshotVersion: Int = 1,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    var snapshot: String = "{}",
    @Column(name = "rules_version", columnDefinition = "TEXT")
    var rulesVersion: String? = null,
    @Column(name = "emailed_at")
    var emailedAt: Instant? = null,
    @Column(name = "email_had_verdict")
    var emailHadVerdict: Boolean? = null,
    @Column(name = "followup_sent_at")
    var followUpSentAt: Instant? = null,
    @Column(name = "telegram_sent_at")
    var telegramSentAt: Instant? = null,
    @Column(name = "decision_at")
    var decisionAt: Instant? = null,
    @Column(name = "decision_action", columnDefinition = "TEXT")
    var decisionAction: String? = null,
    @Column(name = "decision_rank")
    var decisionRank: Int? = null,
    @Column(name = "decision_score")
    var decisionScore: BigDecimal? = null,
    @Column(name = "decision_expiry")
    var decisionExpiry: LocalDate? = null,
    @Column(name = "decision_short_strike")
    var decisionShortStrike: BigDecimal? = null,
    @Column(name = "decision_long_strike")
    var decisionLongStrike: BigDecimal? = null,
    @Column(name = "decision_mid_credit")
    var decisionMidCredit: BigDecimal? = null,
    @Column(name = "shadow_spread_id")
    var shadowSpreadId: UUID? = null,
    @Column(name = "would_gate")
    var wouldGate: Boolean? = null,
)
