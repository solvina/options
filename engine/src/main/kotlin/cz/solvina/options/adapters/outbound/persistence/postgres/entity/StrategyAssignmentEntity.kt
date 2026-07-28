package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One strategy running on one symbol at one timeframe, with optional parameter overrides.
 *
 * [paramsJson] null means "use the strategy's descriptor defaults" — distinct from an empty object
 * only in intent, but the UI shows the difference between "not configured" and "configured empty".
 */
@Entity
@Table(name = "strategy_assignment")
class StrategyAssignmentEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "strategy_id", columnDefinition = "TEXT", nullable = false)
    var strategyId: String = "",
    @Column(columnDefinition = "TEXT", nullable = false)
    var symbol: String = "",
    @Column(columnDefinition = "TEXT", nullable = false)
    var timeframe: String = "1d",
    @Column(name = "params_json", columnDefinition = "TEXT")
    var paramsJson: String? = null,
    @Column(nullable = false)
    var enabled: Boolean = false,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)
