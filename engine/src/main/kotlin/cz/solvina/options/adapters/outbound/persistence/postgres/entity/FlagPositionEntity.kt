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
    @Column(name = "trail_amount", precision = 10, scale = 4)
    var trailAmount: BigDecimal? = null,
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
    @Column(name = "close_reason", columnDefinition = "TEXT")
    var closeReason: String? = null,
    @Column(name = "close_price_actual", precision = 10, scale = 4)
    var closePriceActual: BigDecimal? = null,
    @Column(name = "realized_pnl", precision = 10, scale = 2)
    var realizedPnl: BigDecimal? = null,
    @Column(name = "strategy_name", nullable = false, length = 64)
    var strategyName: String = "bull_flag",
    @Column(name = "actual_entry_price", precision = 10, scale = 4)
    var actualEntryPrice: BigDecimal? = null,
    @Column(name = "highest_price_seen", precision = 10, scale = 4)
    var highestPriceSeen: BigDecimal? = null,
    @Column(name = "lowest_price_seen", precision = 10, scale = 4)
    var lowestPriceSeen: BigDecimal? = null,
    @Column(name = "max_favorable_excursion", precision = 12, scale = 2)
    var maxFavorableExcursion: BigDecimal? = null,
    @Column(name = "max_adverse_excursion", precision = 12, scale = 2)
    var maxAdverseExcursion: BigDecimal? = null,
    @Column(name = "flag_bar_count")
    var flagBarCount: Int? = null,
    @Column(name = "flagpole_bar_count")
    var flagpoleBarCount: Int? = null,
    @Column(name = "flagpole_avg_volume")
    var flagpoleAvgVolume: Long? = null,
    @Column(name = "flag_avg_volume")
    var flagAvgVolume: Long? = null,
    @Column(name = "channel_slope", precision = 10, scale = 7)
    var channelSlope: BigDecimal? = null,
    @Column(name = "market_session", length = 5)
    var marketSession: String? = null,
    @Column(name = "minutes_to_close")
    var minutesToClose: Int? = null,
    @Column(name = "entry_slippage", precision = 10, scale = 4)
    var entrySlippage: BigDecimal? = null,
    @Column(name = "r_multiple", precision = 8, scale = 2)
    var rMultiple: BigDecimal? = null,
    @Column(name = "time_in_trade_seconds")
    var timeInTradeSeconds: Int? = null,
    @Column(name = "atr_at_entry", precision = 10, scale = 4)
    var atrAtEntry: BigDecimal? = null,
    @Column(name = "volume_ma_at_entry")
    var volumeMaAtEntry: Long? = null,
    @Column(name = "flagpole_volume_ratio", precision = 8, scale = 3)
    var flagpoleVolumeRatio: BigDecimal? = null,
    @Column(name = "vwap_at_entry", precision = 10, scale = 4)
    var vwapAtEntry: BigDecimal? = null,
    @Column(name = "day_open_price", precision = 10, scale = 4)
    var dayOpenPrice: BigDecimal? = null,
    @Column(name = "breakout_type", length = 10)
    var breakoutType: String? = null,
    @Column(name = "stop_distance_pct", precision = 8, scale = 4)
    var stopDistancePct: BigDecimal? = null,
    @Column(name = "mfe_r", precision = 8, scale = 2)
    var mfeR: BigDecimal? = null,
    @Column(name = "mae_r", precision = 8, scale = 2)
    var maeR: BigDecimal? = null,
)
