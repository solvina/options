package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** A named, saved backtest parameter preset. [payloadJson] is the full form state (opaque JSON). */
@Entity
@Table(name = "backtest_config")
class BacktestConfigEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(length = 120, unique = true, nullable = false)
    var name: String = "",
    @Column(name = "payload_json", columnDefinition = "TEXT", nullable = false)
    var payloadJson: String = "{}",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,
)
