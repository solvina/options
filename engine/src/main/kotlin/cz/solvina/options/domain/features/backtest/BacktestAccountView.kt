package cz.solvina.options.domain.features.backtest

import java.math.BigDecimal

data class BacktestAccountView(
    val capital: BigDecimal,
    val openPositions: Int,
    val pendingPositions: Int,
)
