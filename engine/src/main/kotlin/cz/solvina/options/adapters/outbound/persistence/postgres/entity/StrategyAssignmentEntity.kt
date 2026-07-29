package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One strategy running on one symbol at one timeframe.
 *
 * Parameters are not here: they live in `strategy_default_params` / `strategy_symbol_params`, so
 * every strategy family is tuned through one path. The `params_json` column was dropped in v38.
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
    @Column(nullable = false)
    var enabled: Boolean = false,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)
