package cz.solvina.options.account

import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.OrphanPositionDetector
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrphanPositionDetectorTest {
    private val detector = OrphanPositionDetector()
    private val expiry = LocalDate.of(2026, 7, 17)
    private val symbol = Symbol("AMD")

    private fun opt(
        sym: String,
        strike: String,
        right: OptionType = OptionType.PUT,
    ) = OptionContract(Symbol(sym), expiry, BigDecimal(strike), right)

    private fun openSpread(
        sold: String,
        bought: String,
        qty: Int = 1,
    ) = BullPutSpread(
        id = UUID.randomUUID(),
        symbol = symbol,
        soldLeg = SpreadLeg(opt("AMD", sold), LegAction.SELL, Money(BigDecimal("1.50")), orderId = 1),
        boughtLeg = SpreadLeg(opt("AMD", bought), LegAction.BUY, Money(BigDecimal("0.50")), orderId = 2),
        creditPerShare = BigDecimal("1.00"),
        maxRiskPerShare = BigDecimal("4.00"),
        quantity = qty,
        status = SpreadStatus.OPEN,
        ivRankAtEntry = 50.0,
        underlyingPriceAtEntry = BigDecimal("420"),
        openedAt = Instant.now(),
    )

    private fun pos(
        sym: String,
        secType: String,
        qty: Int,
        strike: String? = null,
        right: String? = null,
        conId: Int = qty + 1000,
    ) = AccountPosition(
        account = "DU1",
        symbol = sym,
        secType = secType,
        currency = "USD",
        expiry = if (secType == "OPT") expiry else null,
        strike = strike?.let { BigDecimal(it) },
        optionRight = right,
        quantity = BigDecimal(qty),
        avgCost = BigDecimal("100"),
        conId = conId,
    )

    @Test
    fun `a fully-matched bull put spread produces no orphans`() {
        val spreads = listOf(openSpread(sold = "420", bought = "415"))
        val positions =
            listOf(
                pos("AMD", "OPT", qty = -1, strike = "420", right = "P"),
                pos("AMD", "OPT", qty = 1, strike = "415", right = "P"),
            )
        assertEquals(0, detector.detect(spreads, positions).size)
    }

    @Test
    fun `naked short put with no managing spread is an orphan`() {
        val positions = listOf(pos("AMD", "OPT", qty = -18, strike = "420", right = "P"))
        val orphans = detector.detect(emptyList(), positions)
        assertEquals(1, orphans.size)
        assertTrue(orphans[0].reason.contains("no managing OPEN spread"))
    }

    @Test
    fun `short stock is always an orphan`() {
        val orphans = detector.detect(emptyList(), listOf(pos("NOW", "STK", qty = -147)))
        assertEquals(1, orphans.size)
        assertTrue(orphans[0].reason.contains("stock position"))
    }

    @Test
    fun `quantity mismatch against a managed spread is flagged`() {
        val spreads = listOf(openSpread(sold = "420", bought = "415", qty = 1))
        // Held -18 short but spread only manages -1.
        val positions =
            listOf(
                pos("AMD", "OPT", qty = -18, strike = "420", right = "P"),
                pos("AMD", "OPT", qty = 1, strike = "415", right = "P"),
            )
        val orphans = detector.detect(spreads, positions)
        assertEquals(1, orphans.size)
        assertEquals(-18, orphans[0].position.quantity.toInt())
        assertTrue(orphans[0].reason.contains("expected -1, held -18"))
    }

    @Test
    fun `zero-quantity positions are ignored`() {
        val orphans = detector.detect(emptyList(), listOf(pos("AMD", "OPT", qty = 0, strike = "420", right = "P")))
        assertEquals(0, orphans.size)
    }
}
