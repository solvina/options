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

/**
 * A persisted backtest run: the full parameter set ([paramsJson]), queryable summary metrics, and
 * the detailed per-trade report ([tradesJson]). Summary stays as columns so runs can be sorted/
 * filtered; params + trades are JSON blobs so the full request can be recalled/re-run and the
 * per-trade detail inspected.
 */
@Entity
@Table(name = "backtest_run")
class BacktestRunEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(columnDefinition = "TEXT")
    var label: String? = null,
    @Column(nullable = false, columnDefinition = "TEXT")
    var strategy: String = "flag",
    @Column(name = "from_date", nullable = false)
    var fromDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "to_date", nullable = false)
    var toDate: LocalDate = LocalDate.EPOCH,
    @Column(nullable = false, columnDefinition = "TEXT")
    var symbols: String = "",
    @Column(name = "params_json", nullable = false, columnDefinition = "TEXT")
    var paramsJson: String = "{}",
    @Column(name = "initial_capital", nullable = false, precision = 18, scale = 2)
    var initialCapital: BigDecimal = BigDecimal.ZERO,
    @Column(name = "final_capital", nullable = false, precision = 18, scale = 2)
    var finalCapital: BigDecimal = BigDecimal.ZERO,
    @Column(name = "total_pnl", nullable = false, precision = 18, scale = 2)
    var totalPnl: BigDecimal = BigDecimal.ZERO,
    @Column(name = "total_pnl_pct", nullable = false, precision = 10, scale = 4)
    var totalPnlPct: BigDecimal = BigDecimal.ZERO,
    @Column(name = "trade_count", nullable = false)
    var tradeCount: Int = 0,
    @Column(name = "win_count", nullable = false)
    var winCount: Int = 0,
    @Column(name = "loss_count", nullable = false)
    var lossCount: Int = 0,
    @Column(name = "eod_count", nullable = false)
    var eodCount: Int = 0,
    @Column(name = "win_rate", nullable = false)
    var winRate: Double = 0.0,
    @Column(name = "avg_r_multiple", precision = 10, scale = 4)
    var avgRMultiple: BigDecimal? = null,
    @Column(name = "avg_win_r", precision = 10, scale = 4)
    var avgWinR: BigDecimal? = null,
    @Column(name = "avg_loss_r", precision = 10, scale = 4)
    var avgLossR: BigDecimal? = null,
    @Column(name = "profit_factor", precision = 12, scale = 4)
    var profitFactor: BigDecimal? = null,
    @Column(name = "max_drawdown_pct", nullable = false, precision = 10, scale = 4)
    var maxDrawdownPct: BigDecimal = BigDecimal.ZERO,
    @Column(name = "trades_json", nullable = false, columnDefinition = "TEXT")
    var tradesJson: String = "[]",
)
