package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** One revaluation of a would-be trade (v48). Append-only. */
@Entity
@Table(name = "shadow_spread_mark")
class ShadowSpreadMarkEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "shadow_spread_id", nullable = false)
    var shadowSpreadId: UUID = UUID(0, 0),
    @Column(name = "valued_at", nullable = false)
    var valuedAt: Instant = Instant.EPOCH,
    var value: BigDecimal? = null,
    var underlying: BigDecimal? = null,
    @Column(columnDefinition = "TEXT", nullable = false)
    var status: String = "OPEN",
)
