package cz.solvina.options.scanner

import cz.solvina.options.domain.features.scanner.SpreadSelectionRules
import cz.solvina.options.domain.features.scanner.SpreadSelectionRules.ExpiryPick
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpreadSelectionRulesTest {
    private val today = LocalDate.of(2026, 10, 28)
    private val nov27 = LocalDate.of(2026, 11, 27) // 30 DTE
    private val dec4 = LocalDate.of(2026, 12, 4) // 37 DTE — nearest to 45
    private val jan15 = LocalDate.of(2027, 1, 15) // out of band

    @Test
    fun `CRM - the expiry before earnings is picked when the preferred one spans them`() {
        val pick = SpreadSelectionRules.pickExpiry(listOf(nov27, dec4, jan15), today, 30, 50, 45, LocalDate.of(2026, 12, 2))

        assertEquals(ExpiryPick.Picked(nov27), pick)
    }

    @Test
    fun `all in-band expiries spanning earnings is a rejection naming the preferred one`() {
        val pick = SpreadSelectionRules.pickExpiry(listOf(nov27, dec4), today, 30, 50, 45, LocalDate.of(2026, 11, 2))

        assertEquals(ExpiryPick.AllSpanEarnings(dec4, LocalDate.of(2026, 11, 2)), pick)
    }

    @Test
    fun `earnings on the expiry day itself still blocks that expiry`() {
        val pick = SpreadSelectionRules.pickExpiry(listOf(nov27, dec4), today, 30, 50, 45, dec4)

        assertEquals(ExpiryPick.Picked(nov27), pick)
    }

    @Test
    fun `width cap allows up to twice the target`() {
        assertFalse(SpreadSelectionRules.widthExceedsCap(BigDecimal("10"), BigDecimal("5"), 2.0))
        assertTrue(SpreadSelectionRules.widthExceedsCap(BigDecimal("15"), BigDecimal("5"), 2.0))
    }
}
