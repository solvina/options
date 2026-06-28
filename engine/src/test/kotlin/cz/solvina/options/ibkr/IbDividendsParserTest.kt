package cz.solvina.options.ibkr

import cz.solvina.options.adapters.outbound.ibkr.market.parseIbDividends
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Parses the IB_DIVIDENDS tick string, using the real samples observed from the probe. */
class IbDividendsParserTest {
    @Test
    fun `parses next dividend date and amount (TER sample)`() {
        val info = parseIbDividends("0.50,0.52,20260904,0.13")
        assertEquals(LocalDate.of(2026, 9, 4), info.exDividendDate)
        assertEquals(0, BigDecimal("0.13").compareTo(info.amount!!))
    }

    @Test
    fun `parses AAPL sample`() {
        val info = parseIbDividends("1.05,1.09,20260810,0.27")
        assertEquals(LocalDate.of(2026, 8, 10), info.exDividendDate)
        assertEquals(0, BigDecimal("0.27").compareTo(info.amount!!))
    }

    @Test
    fun `non-paying name yields empty info`() {
        val info = parseIbDividends(",,,")
        assertNull(info.exDividendDate)
        assertNull(info.amount)
    }

    @Test
    fun `handles trailing amounts but no forward date`() {
        val info = parseIbDividends("1.05,1.09,,")
        assertNull(info.exDividendDate)
        assertNull(info.amount)
    }
}
