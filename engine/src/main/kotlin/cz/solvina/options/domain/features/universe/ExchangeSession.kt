package cz.solvina.options.domain.features.universe

import java.time.LocalTime
import java.time.ZoneId

/**
 * Regular trading window of a market ("US", "EU"), in that market's own timezone. Seeded in
 * `exchange_session` and referenced by [InstrumentRouting.marketExchange].
 *
 * This is the coarse weekday window; [MarketCalendarPort] refines it with the broker's liquid
 * hours (holidays, half-days) when the calendar is warm.
 */
data class ExchangeSession(
    val name: String,
    val zone: ZoneId,
    val open: LocalTime,
    val close: LocalTime,
) {
    companion object {
        /** Fallback when a symbol's [InstrumentRouting.marketExchange] has no `exchange_session` row. */
        val US =
            ExchangeSession(
                name = "US",
                zone = ZoneId.of("America/New_York"),
                open = LocalTime.of(9, 30),
                close = LocalTime.of(16, 0),
            )
    }
}
