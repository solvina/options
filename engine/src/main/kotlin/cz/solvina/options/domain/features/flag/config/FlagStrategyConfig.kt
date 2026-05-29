package cz.solvina.options.domain.features.flag.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Strategy parameters bound from application.yml under `flag:`. Not user-editable at runtime. */
@ConfigurationProperties(prefix = "flag")
data class FlagStrategyConfig(
    val watchlist: List<String> = listOf("SPY", "QQQ", "AAPL", "MSFT", "NVDA"),
    val atrPeriod: Int = 14,
    val atrMultiplier: Double = 2.0,
    val volumeMaPeriod: Int = 20,
    val volumeSpikeMultiplier: Double = 1.5,
    val poleMinBars: Int = 5,
    val poleMaxBars: Int = 10,
    val flagMinBars: Int = 5,
    val flagMaxBars: Int = 20,
    val maxRetracementPct: Double = 0.50,
    val historicalBootstrapDays: Int = 3,
)
