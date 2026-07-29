package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * The saved baseline for one strategy: what every symbol without an override of its own runs.
 *
 * There is no row until someone saves one, and Reset deletes it rather than writing the defaults
 * back. That is deliberate — the absolute defaults exist only as `ParamDescriptor.default`, so a
 * later change to a default reaches every un-tuned strategy instead of being shadowed by a stale
 * copy in this table.
 */
@Entity
@Table(name = "strategy_default_params")
class StrategyDefaultParamsEntity(
    @Id
    @Column(name = "strategy_id", columnDefinition = "TEXT")
    var strategyId: String = "",
    @Column(name = "params_json", columnDefinition = "TEXT", nullable = false)
    var paramsJson: String = "{}",
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)

/**
 * A per-symbol parameter override, layered over [StrategyDefaultParamsEntity].
 *
 * [timeframe] is `*` for strategies with a single natural timeframe (the flag scanner is always on
 * 5-minute candles); stock strategies that run one symbol at two timeframes may store a row per
 * timeframe. See the v38 migration for why this is a sentinel rather than NULL.
 */
@Entity
@Table(name = "strategy_symbol_params")
class StrategySymbolParamsEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "strategy_id", columnDefinition = "TEXT", nullable = false)
    var strategyId: String = "",
    @Column(columnDefinition = "TEXT", nullable = false)
    var symbol: String = "",
    @Column(columnDefinition = "TEXT", nullable = false)
    var timeframe: String = "*",
    @Column(name = "params_json", columnDefinition = "TEXT", nullable = false)
    var paramsJson: String = "{}",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH,
)
