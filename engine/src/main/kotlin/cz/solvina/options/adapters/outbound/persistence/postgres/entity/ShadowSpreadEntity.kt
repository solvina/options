package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Row backing [cz.solvina.options.domain.features.shadow.ShadowSpread]. See v41. */
@Entity
@Table(name = "shadow_spreads")
class ShadowSpreadEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "strategy_id", columnDefinition = "TEXT", nullable = false)
    var strategyId: String = "",
    @Column(columnDefinition = "TEXT", nullable = false)
    var symbol: String = "",
    @Column(name = "option_type", columnDefinition = "TEXT", nullable = false)
    var optionType: String = "PUT",
    @Column(nullable = false)
    var expiry: LocalDate = LocalDate.MIN,
    @Column(name = "sold_strike", nullable = false)
    var soldStrike: BigDecimal = BigDecimal.ZERO,
    @Column(name = "bought_strike", nullable = false)
    var boughtStrike: BigDecimal = BigDecimal.ZERO,
    @Column(name = "opened_at", nullable = false)
    var openedAt: Instant = Instant.EPOCH,
    @Column(name = "source_outcome", columnDefinition = "TEXT", nullable = false)
    var sourceOutcome: String = "",
    @Column(name = "mid_credit", nullable = false)
    var midCredit: BigDecimal = BigDecimal.ZERO,
    @Column(name = "floor_credit")
    var floorCredit: BigDecimal? = null,
    @Column(name = "natural_credit")
    var naturalCredit: BigDecimal? = null,
    @Column(name = "underlying_at_entry")
    var underlyingAtEntry: BigDecimal? = null,
    @Column(name = "iv_rank_at_entry")
    var ivRankAtEntry: BigDecimal? = null,
    @Column(columnDefinition = "TEXT", nullable = false)
    var status: String = "OPEN",
    @Column(name = "close_reason", columnDefinition = "TEXT")
    var closeReason: String? = null,
    @Column(name = "closed_at")
    var closedAt: Instant? = null,
    @Column(name = "exit_value")
    var exitValue: BigDecimal? = null,
    @Column(name = "last_value")
    var lastValue: BigDecimal? = null,
    @Column(name = "last_underlying")
    var lastUnderlying: BigDecimal? = null,
    @Column(name = "last_valued_at")
    var lastValuedAt: Instant? = null,
    @Column(nullable = false)
    var backfilled: Boolean = false,
)
