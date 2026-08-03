package cz.solvina.options.market

import cz.solvina.options.domain.features.market.model.ComboQuote
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins IBKR's BAG sign convention against quotes captured in the first live session that requested
 * combo market data (2026-08-03 15:30 CEST). The first implementation read the entry credit off the
 * bid and produced floors above the leg-derived mid; these cases exist so that cannot return.
 */
class ComboQuoteTest {
    private fun quote(
        bid: String,
        ask: String,
    ) = ComboQuote(BigDecimal(bid), BigDecimal(ask))

    @Test
    fun `entry credit is the negated ask, not the bid`() {
        // HON 230P/220P — bid -3.05 would imply a $3.05 floor against a $2.175 mid: unfillable.
        assertEquals(
            BigDecimal("0.6000"),
            quote("-3.05", "-0.60").achievableCredit(BigDecimal("2.1750")),
        )
    }

    @Test
    fun `bear-call package reads the same way`() {
        // TMUS 185C/190C.
        assertEquals(
            BigDecimal("0.5000"),
            quote("-2.70", "-0.50").achievableCredit(BigDecimal("1.5000")),
        )
    }

    @Test
    fun `a spread paying nothing returns a negative credit rather than null`() {
        // ICE — agreed to the cent with the leg natural cross (-0.05), which is what pinned the
        // convention. Must be returned so the caller rejects on a real number, not a fallback.
        assertEquals(
            BigDecimal("-0.0500"),
            quote("-1.90", "0.05").achievableCredit(BigDecimal("0.9250")),
        )
    }

    @Test
    fun `rejects the IBKR placeholder ask`() {
        assertNull(quote("-3.05", "-1").achievableCredit(BigDecimal("2.1750")))
    }

    @Test
    fun `rejects a credit implausibly far above the leg mid`() {
        // The ask is the worse side, so a credit well above mid means the two disagree beyond what
        // book skew explains — discard and let the caller fall back.
        assertNull(quote("-9.00", "-8.00").achievableCredit(BigDecimal("2.0000")))
    }

    @Test
    fun `tolerates a combo book modestly tighter than the leg mid`() {
        assertEquals(
            BigDecimal("2.2000"),
            quote("-2.60", "-2.20").achievableCredit(BigDecimal("2.0000")),
        )
    }

    @Test
    fun `guards a non-positive reference mid`() {
        assertNull(quote("-3.05", "-0.60").achievableCredit(BigDecimal.ZERO))
    }
}
