package cz.solvina.options.backtest

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.LocalDate

data class BacktestResult(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val initialCapital: BigDecimal,
    val finalCapital: BigDecimal,
    val totalPnl: BigDecimal,
    val tradeCount: Int,
    val winCount: Int,
    val winRate: Double,
    val maxDrawdownPct: Double,
    val trades: List<ClosedTrade>,
) {
    fun summary(): String =
        """
        Backtest $startDate → $endDate
        Capital:   ${'$'}$initialCapital → ${'$'}$finalCapital  (P&L: ${'$'}$totalPnl)
        Trades:    $tradeCount  Wins: $winCount  Win rate: ${"%.1f".format(winRate * 100)} %
        Max DD:    ${"%.2f".format(maxDrawdownPct)} %
        """.trimIndent()
}

data class ClosedTrade(
    val symbol: Symbol,
    val openDate: LocalDate,
    val closeDate: LocalDate,
    val creditPerShare: BigDecimal,
    val closePricePerShare: BigDecimal,
    /** Positive = profit (collected more than paid to close). */
    val pnlPerShare: BigDecimal,
    /** pnlPerShare × 100 — one options contract covers 100 shares. */
    val pnlPerContract: BigDecimal,
    val closeReason: String?,
)
