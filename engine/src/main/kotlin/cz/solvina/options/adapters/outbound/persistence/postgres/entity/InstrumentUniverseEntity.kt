package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "instrument_universe")
class InstrumentUniverseEntity(
    @Id
    @Column(length = 10)
    var symbol: String = "",
    @Column(nullable = false)
    var enabled: Boolean = true,
    @Column(name = "iv_rank_threshold", precision = 5, scale = 2)
    var ivRankThreshold: BigDecimal? = null,
    @Column(name = "min_dte")
    var minDte: Int? = null,
    @Column(name = "max_dte")
    var maxDte: Int? = null,
    @Column(name = "preferred_dte")
    var preferredDte: Int? = null,
    @Column(name = "target_delta", precision = 4, scale = 3)
    var targetDelta: BigDecimal? = null,
    @Column(name = "delta_min", precision = 4, scale = 3)
    var deltaMin: BigDecimal? = null,
    @Column(name = "delta_max", precision = 4, scale = 3)
    var deltaMax: BigDecimal? = null,
    @Column(name = "spread_width_usd", precision = 8, scale = 2)
    var spreadWidthUsd: BigDecimal? = null,
    @Column(name = "min_credit_per_share", precision = 8, scale = 4)
    var minCreditPerShare: BigDecimal? = null,
    @Column(name = "max_risk_percent", precision = 5, scale = 4)
    var maxRiskPercent: BigDecimal? = null,
    @Column(name = "take_profit_percent", precision = 5, scale = 4)
    var takeProfitPercent: BigDecimal? = null,
    @Column(name = "stop_loss_percent", precision = 5, scale = 4)
    var stopLossPercent: BigDecimal? = null,
    @Column(name = "time_profit_dte")
    var timeProfitDte: Int? = null,
    @Column(name = "notes", length = 500)
    var notes: String? = null,
)
