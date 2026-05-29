package cz.solvina.options.adapters.outbound.persistence.postgres.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "flag_trading_config")
class FlagTradingConfigEntity(
    @Id
    var id: Long = 1L,
    @Column(name = "risk_per_trade", nullable = false, precision = 10, scale = 2)
    var riskPerTrade: BigDecimal = BigDecimal("100.00"),
    @Column(name = "max_open_positions", nullable = false)
    var maxOpenPositions: Int = 3,
    @Column(nullable = false)
    var enabled: Boolean = true,
    @Column(name = "entry_block_minutes_before_close", nullable = false)
    var entryBlockMinutesBeforeClose: Int = 120,
    @Column(name = "eod_liq_minutes_before_close", nullable = false)
    var eodLiqMinutesBeforeClose: Int = 15,
)
