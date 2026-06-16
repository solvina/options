package cz.solvina.options.domain.features.universe

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal

data class InstrumentConfig(
    val symbol: Symbol,
    val enabled: Boolean = true,
    // Flag-strategy membership (intraday momentum). Independent of [enabled] (spread membership).
    val flagEnabled: Boolean = false,
    // Entry filter overrides — null means use global ScannerConfig default
    val ivRankThreshold: Double? = null,
    val minDte: Int? = null,
    val maxDte: Int? = null,
    val preferredDte: Int? = null,
    val targetDelta: Double? = null,
    val deltaMin: Double? = null,
    val deltaMax: Double? = null,
    val spreadWidthUsd: BigDecimal? = null,
    val minCreditPerShare: BigDecimal? = null,
    val maxRiskPercent: Double? = null,
    // Exit rule overrides — null means use global ScannerConfig default
    val takeProfitPercent: Double? = null,
    val stopLossPercent: Double? = null,
    val timeProfitDte: Int? = null,
    val notes: String? = null,
)
