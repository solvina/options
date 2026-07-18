package cz.solvina.options.domain.features.universe

import cz.solvina.options.domain.models.Symbol
import java.time.LocalDate

/**
 * Source of the next scheduled earnings-report date for a symbol (implemented over the public
 * Nasdaq earnings-date API, which serves Zacks data). Returns null when the source has no date or
 * the fetch fails — the caller keeps whatever it already stored.
 */
interface EarningsDataPort {
    suspend fun fetchNextEarningsDate(symbol: Symbol): LocalDate?
}
