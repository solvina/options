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

@Entity
@Table(name = "flag_positions")
class FlagPositionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(nullable = false, length = 10)
    var symbol: String = "",
    @Column(nullable = false, length = 30)
    var status: String = "",
    @Column(name = "entry_order_id", nullable = false)
    var entryOrderId: Int = 0,
    @Column(name = "stop_loss_order_id", nullable = false)
    var stopLossOrderId: Int = 0,
    @Column(name = "profit_target_order_id", nullable = false)
    var profitTargetOrderId: Int = 0,
    @Column(name = "entry_price", nullable = false, precision = 10, scale = 4)
    var entryPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "stop_loss_price", nullable = false, precision = 10, scale = 4)
    var stopLossPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "profit_target_price", nullable = false, precision = 10, scale = 4)
    var profitTargetPrice: BigDecimal = BigDecimal.ZERO,
    @Column(nullable = false)
    var shares: Int = 0,
    @Column(name = "risk_amount", nullable = false, precision = 10, scale = 2)
    var riskAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "flagpole_height", precision = 10, scale = 4)
    var flagpoleHeight: BigDecimal? = null,
    @Column(name = "flag_retracement", precision = 5, scale = 4)
    var flagRetracement: BigDecimal? = null,
    @Column(name = "resistance_at_entry", precision = 10, scale = 4)
    var resistanceAtEntry: BigDecimal? = null,
    @Column(name = "pattern_started_at")
    var patternStartedAt: Instant? = null,
    @Column(name = "opened_at", nullable = false)
    var openedAt: Instant = Instant.now(),
    @Column(name = "closed_at")
    var closedAt: Instant? = null,
    @Column(name = "close_reason", length = 50)
    var closeReason: String? = null,
    @Column(name = "close_price_actual", precision = 10, scale = 4)
    var closePriceActual: BigDecimal? = null,
    @Column(name = "realized_pnl", precision = 10, scale = 2)
    var realizedPnl: BigDecimal? = null,
)
