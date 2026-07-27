package cz.solvina.options.domain.features.strategy

import java.math.BigDecimal
import java.time.Instant

/**
 * One completed round trip produced by a [StockStrategy] run. Strategy-agnostic: every strategy in
 * the library reports trades in this shape so results, analytics and sweeps stay comparable across
 * strategies.
 *
 * Field names match the former `RuleBacktestStrategy.RuleTrade` exactly, so the backtest JSON the
 * UI consumes is unchanged by the move onto the strategy seam.
 */
data class StrategyTrade(
    val symbol: String,
    val entryAt: Instant,
    val entryPrice: BigDecimal,
    val exitAt: Instant,
    val exitPrice: BigDecimal,
    val closeReason: String,
    val shares: Int,
    val pnl: BigDecimal,
)
