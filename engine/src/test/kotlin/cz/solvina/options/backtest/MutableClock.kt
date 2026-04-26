package cz.solvina.options.backtest

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A mutable clock for backtesting. Advance day by day via [advanceTo].
 * Represents market open (09:30 ET) on the given date so DTE calculations
 * based on LocalDate.now(clock) behave naturally.
 */
class MutableClock(
    initialDate: LocalDate,
    private val zone: ZoneId = ZoneId.of("America/New_York"),
) : Clock() {
    private var current: Instant = initialDate.toMarketOpen(zone)

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(LocalDate.now(this), zone)

    override fun instant(): Instant = current

    fun advanceTo(date: LocalDate) {
        current = date.toMarketOpen(zone)
    }

    fun currentDate(): LocalDate = LocalDate.now(this)

    private fun LocalDate.toMarketOpen(zone: ZoneId): Instant = atTime(9, 30).atZone(zone).toInstant()
}
