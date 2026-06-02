package cz.solvina.options.domain.features.flag.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Strategy parameters bound from application.yml under `flag:`. Not user-editable at runtime. */
@ConfigurationProperties(prefix = "flag")
data class FlagStrategyConfig(
    val usWatchlist: List<String> = listOf("SPY", "QQQ", "AAPL", "MSFT", "NVDA"),
    val euWatchlist: List<String> = listOf("SAP", "ASML", "SIE", "ALV"),
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

    // Entry quality filters — default to permissive (no filter) so existing live behaviour is unchanged
    /** Skip entries for this many minutes after session open (avoids opening-bell chop). */
    val skipFirstRthMinutes: Int = 0,
    /** Require downward-sloping flag channel (rising wedge is not a bull flag). */
    val requireNegativeChannelSlope: Boolean = false,
    /** Minimum flagpole height as a multiple of ATR at entry. */
    val minFlagpoleAtrMultiple: Double = 0.0,
    /** Maximum flagpole height as a multiple of ATR at entry (filters over-extended moves). */
    val maxFlagpoleAtrMultiple: Double = 99.0,
    /** Minimum flag retracement as a fraction (e.g. 0.25 = 25%). */
    val minFlagRetracementPct: Double = 0.0,
    /** Minimum number of flag bars before an entry is allowed. */
    val minFlagBarsForEntry: Int = 1,
)
