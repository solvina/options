package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "bull_put_spreads")
class BullPutSpreadEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(nullable = false, length = 10)
    var symbol: String = "",
    @Column(nullable = false, length = 30)
    var status: String = "",
    @Column(name = "sold_strike", nullable = false, precision = 10, scale = 2)
    var soldStrike: BigDecimal = BigDecimal.ZERO,
    @Column(name = "bought_strike", nullable = false, precision = 10, scale = 2)
    var boughtStrike: BigDecimal = BigDecimal.ZERO,
    @Column(name = "expiry_date", nullable = false)
    var expiryDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "credit_per_share", nullable = false, precision = 10, scale = 4)
    var creditPerShare: BigDecimal = BigDecimal.ZERO,
    @Column(name = "max_risk_per_share", nullable = false, precision = 10, scale = 4)
    var maxRiskPerShare: BigDecimal = BigDecimal.ZERO,
    @Column(nullable = false)
    var quantity: Int = 1,
    @Column(name = "sold_order_id")
    var soldOrderId: Int? = null,
    @Column(name = "bought_order_id")
    var boughtOrderId: Int? = null,
    @Column(name = "iv_rank_at_entry", precision = 5, scale = 2)
    var ivRankAtEntry: BigDecimal? = null,
    @Column(name = "underlying_price_at_entry", precision = 10, scale = 2)
    var underlyingPriceAtEntry: BigDecimal? = null,
    @Column(name = "opened_at", nullable = false)
    var openedAt: Instant = Instant.now(),
    @Column(name = "closed_at")
    var closedAt: Instant? = null,
    @Column(name = "close_reason", columnDefinition = "TEXT")
    var closeReason: String? = null,
    @Column(name = "close_price_per_share", precision = 10, scale = 4)
    var closePricePerShare: BigDecimal? = null,
    @Column(name = "last_spread_value", precision = 10, scale = 4)
    var lastSpreadValue: BigDecimal? = null,
    @Column(name = "underlying_price_at_exit", precision = 10, scale = 2)
    var underlyingPriceAtExit: BigDecimal? = null,
    @Column(name = "iv_rank_at_exit", precision = 5, scale = 2)
    var ivRankAtExit: BigDecimal? = null,
)
