package cz.solvina.options.domain.features.bars

/**
 * Bar timeframe for the store + historical fetch. The timeframe is a property of the *series*, not
 * of an individual [FiveMinuteBar] (which is just OHLCV + time), so it's threaded through the ports
 * rather than baked into the bar type. Defaults keep every existing 5-min caller byte-for-byte
 * unchanged; only the multi-timeframe backtest paths pass DAILY / FOUR_HOUR.
 */
enum class Timeframe(
    /** InfluxDB `interval` tag value + API string. */
    val label: String,
    /** IBKR reqHistoricalData `barSizeSetting`. */
    val ibkrBarSize: String,
    /** Max days per IBKR historical request at this bar size (finer bars → shorter allowed spans). */
    val maxChunkDays: Long,
    /**
     * Min stored bars for a trading day to count as "covered" during gap detection. A trading day
     * below this is treated as a hole and its chunk is re-fetched. ~half a full RTH session for
     * intraday bars (5-min RTH ≈ 78, half-day ≈ 42) so holes and heavy truncation are caught while
     * normal and half sessions are not; 1 for coarse bars where a day is a single bar.
     */
    val minBarsPerDay: Int,
) {
    FIVE_MIN("5min", "5 mins", 59, 40),
    FOUR_HOUR("4h", "4 hours", 180, 1),
    DAILY("1d", "1 day", 3650, 1),
    ;

    companion object {
        fun fromLabel(label: String): Timeframe = entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: FIVE_MIN
    }
}
