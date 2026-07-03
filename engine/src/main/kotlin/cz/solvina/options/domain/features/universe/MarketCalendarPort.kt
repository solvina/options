package cz.solvina.options.domain.features.universe

import cz.solvina.options.domain.models.Symbol
import java.time.LocalDate
import java.time.LocalTime

/** A single day's regular-session schedule for a market, sourced from the broker's liquid hours. */
sealed interface DaySession {
    /** Market closed all day (weekend or holiday). */
    data object Closed : DaySession

    /** Regular session runs [open, close) in the market's local timezone. */
    data class Open(
        val open: LocalTime,
        val close: LocalTime,
    ) : DaySession
}

/**
 * Authoritative trading-calendar lookup backed by IBKR liquid hours. Answers a symbol's regular
 * session for a given date — capturing holidays and half-days that a fixed weekday window cannot.
 * Returns null when the calendar is not (yet) known, so callers fall back to a coarser default.
 */
interface MarketCalendarPort {
    fun sessionFor(
        symbol: Symbol,
        date: LocalDate,
    ): DaySession?
}
