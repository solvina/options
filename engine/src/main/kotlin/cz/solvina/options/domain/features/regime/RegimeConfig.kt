package cz.solvina.options.domain.features.regime

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Market-regime parameters (`regime.*`). Trend (SMA-fast vs SMA-slow) + momentum (RSI) combine into
 * a directional bias. When [gatingEnabled] is true the scanner uses that bias to pick bull put vs
 * bear call per symbol (see ScannerService); when false the signal is observe-only (logged, no gate).
 */
@ConfigurationProperties("regime")
data class RegimeConfig(
    val lookbackDays: Int = 250,
    val smaFast: Int = 50,
    val smaSlow: Int = 200,
    val rsiPeriod: Int = 14,
    val rsiOverbought: Double = 70.0,
    val rsiOversold: Double = 30.0,
    /** false = observe-only (log the bias); true = bias gates strategy selection. */
    val gatingEnabled: Boolean = false,
)
