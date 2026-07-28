package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Row backing [cz.solvina.options.domain.features.strategy.live.StockPosition]. See v37. */
@Entity
@Table(name = "stock_position")
class StockPositionEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "strategy_id", columnDefinition = "TEXT", nullable = false)
    var strategyId: String = "",
    @Column(name = "assignment_id", nullable = false)
    var assignmentId: UUID = UUID.randomUUID(),
    @Column(name = "params_json", columnDefinition = "TEXT", nullable = false)
    var paramsJson: String = "{}",
    @Column(columnDefinition = "TEXT", nullable = false)
    var symbol: String = "",
    @Column(columnDefinition = "TEXT", nullable = false)
    var timeframe: String = "1d",
    @Column(columnDefinition = "TEXT", nullable = false)
    var status: String = "PENDING",
    @Column(name = "entry_order_id")
    var entryOrderId: Int? = null,
    @Column(name = "stop_order_id")
    var stopOrderId: Int? = null,
    @Column(name = "target_order_id")
    var targetOrderId: Int? = null,
    @Column(name = "close_order_id")
    var closeOrderId: Int? = null,
    @Column(name = "signal_price", nullable = false)
    var signalPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "limit_price", nullable = false)
    var limitPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "stop_price", nullable = false)
    var stopPrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "target_price")
    var targetPrice: BigDecimal? = null,
    @Column(nullable = false)
    var shares: Int = 0,
    @Column(name = "risk_amount")
    var riskAmount: BigDecimal? = null,
    @Column(name = "actual_entry_price")
    var actualEntryPrice: BigDecimal? = null,
    @Column(name = "close_price")
    var closePrice: BigDecimal? = null,
    @Column(name = "realized_pnl")
    var realizedPnl: BigDecimal? = null,
    @Column(name = "close_reason", columnDefinition = "TEXT")
    var closeReason: String? = null,
    @Column(name = "highest_price_seen")
    var highestPriceSeen: BigDecimal? = null,
    @Column(name = "lowest_price_seen")
    var lowestPriceSeen: BigDecimal? = null,
    @Column(name = "signalled_at", nullable = false)
    var signalledAt: Instant = Instant.EPOCH,
    @Column(name = "opened_at")
    var openedAt: Instant? = null,
    @Column(name = "closed_at")
    var closedAt: Instant? = null,
)
