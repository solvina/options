package cz.solvina.options.domain.features.regime

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Market-regime parameters (`regime.*`). Observe-only for now — the signal is computed and logged
 * but does not gate trading. (Directional gating + its `enabled`/`mode` knobs are a later phase.)
 */
@ConfigurationProperties("regime")
data class RegimeConfig(
    val lookbackDays: Int = 250,
    val smaFast: Int = 50,
    val smaSlow: Int = 200,
    val cacheTtlHours: Long = 24,
)
