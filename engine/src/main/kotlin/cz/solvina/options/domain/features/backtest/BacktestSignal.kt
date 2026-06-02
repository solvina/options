package cz.solvina.options.domain.features.backtest

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal

sealed interface BacktestSignal {
    data class OpenBracket(
        val tradeId: String,
        val symbol: Symbol,
        val shares: Int,
        val entryPrice: BigDecimal,
        val stopLossPrice: BigDecimal,
        val profitTargetPrice: BigDecimal,
    ) : BacktestSignal
}
