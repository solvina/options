package cz.solvina.options.domain.features.universe

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.LocalDate

data class InstrumentConfig(
    val symbol: Symbol,
    val enabled: Boolean = true,
    // Flag-strategy membership (intraday momentum). Independent of [enabled] (spread membership).
    val flagEnabled: Boolean = false,
    // GICS sector label (e.g. "Information Technology"). Backs the per-sector open-spread cap that
    // keeps one theme from dominating the book. Null = unknown (fails open — not counted/limited).
    val sector: String? = null,
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
    // Dividend assignment protection (bear call, US/American-style). Populated by the dividend-data
    // refresh job (future); null until then — ex-dividend date and the upcoming per-share amount.
    val exDividendDate: LocalDate? = null,
    val nextDividendAmount: BigDecimal? = null,
    // Next scheduled earnings report, refreshed daily by EarningsRefreshService. Both spread
    // selectors reject entries whose expiry spans this date. Null / past dates = no gate.
    val nextEarningsDate: LocalDate? = null,
    val notes: String? = null,
)
