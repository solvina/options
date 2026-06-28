package cz.solvina.options.domain.features.universe

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Forward dividend info for a symbol.
 *
 * NOTE on [exDividendDate]: sourced from the IBKR IB_DIVIDENDS tick's "next dividend date". IBKR's
 * semantics for that field (ex-date vs payable date) are not crisply documented and appear to vary,
 * so this should be validated against known ex-dates before bear call is enabled for live assignment
 * protection. Operators can override per-symbol via the universe row.
 */
data class DividendInfo(
    val exDividendDate: LocalDate?,
    val amount: BigDecimal?,
)

/** Source of forward dividend info for a symbol (implemented over the IBKR IB_DIVIDENDS tick). */
interface DividendDataPort {
    suspend fun fetchDividendInfo(symbol: Symbol): DividendInfo?
}
