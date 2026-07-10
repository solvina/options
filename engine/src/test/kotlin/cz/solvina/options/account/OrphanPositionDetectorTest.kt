package cz.solvina.options.account

import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.OrphanPositionDetector
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.spread.model.BearCallSpread
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

    private fun openBearCall(
        sold: String,
        bought: String,
        qty: Int = 1,
    ) = BearCallSpread(
        id = UUID.randomUUID(),
        symbol = symbol,
        soldLeg = SpreadLeg(opt("AMD", sold, OptionType.CALL), LegAction.SELL, Money(BigDecimal("1.50")), orderId = 3),
        boughtLeg = SpreadLeg(opt("AMD", bought, OptionType.CALL), LegAction.BUY, Money(BigDecimal("0.50")), orderId = 4),
        creditPerShare = BigDecimal("1.00"),
        maxRiskPerShare = BigDecimal("4.00"),
        quantity = qty,
        status = SpreadStatus.OPEN,
        ivRankAtEntry = 50.0,
        underlyingPriceAtEntry = BigDecimal("420"),
        openedAt = Instant.now(),
    )

    private fun openFlag(
        sym: String,
        shares: Int,
    ) = FlagPosition(
        id = UUID.randomUUID(),
        symbol = Symbol(sym),
        status = FlagStatus.OPEN,
        entryOrderId = 10,
        stopLossOrderId = 11,
        profitTargetOrderId = 12,
        entryPrice = BigDecimal("100"),
        stopLossPrice = BigDecimal("95"),
        profitTargetPrice = BigDecimal("110"),
        shares = shares,
        riskAmount = BigDecimal("500"),
        flagpoleHeight = null,
        flagRetracement = null,
        resistanceAtEntry = null,
        patternStartedAt = null,
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
        assertEquals(0, detector.detect(spreads, emptyList(), positions).size)
    }

    @Test
    fun `a fully-matched bear call spread produces no orphans`() {
        val spreads = listOf(openBearCall(sold = "430", bought = "435"))
        val positions =
            listOf(
                pos("AMD", "OPT", qty = -1, strike = "430", right = "C"),
                pos("AMD", "OPT", qty = 1, strike = "435", right = "C"),
            )
        assertEquals(0, detector.detect(spreads, emptyList(), positions).size)
    }

    @Test
    fun `naked short put with no managing spread is an orphan`() {
        val positions = listOf(pos("AMD", "OPT", qty = -18, strike = "420", right = "P"))
        val orphans = detector.detect(emptyList(), emptyList(), positions)
        assertEquals(1, orphans.size)
        assertTrue(orphans[0].reason.contains("no managing OPEN spread"))
    }

    @Test
    fun `stock with no managing flag is an orphan`() {
        val orphans = detector.detect(emptyList(), emptyList(), listOf(pos("NOW", "STK", qty = -147)))
        assertEquals(1, orphans.size)
        assertTrue(orphans[0].reason.contains("stock position"))
    }

    @Test
    fun `long stock fully explained by an open bull flag produces no orphans`() {
        val flags = listOf(openFlag("TSLA", shares = 58))
        val positions = listOf(pos("TSLA", "STK", qty = 58))
        assertEquals(0, detector.detect(emptyList(), flags, positions).size)
    }

    @Test
    fun `stock quantity mismatch against a managed flag is flagged`() {
        val flags = listOf(openFlag("TSLA", shares = 58))
        // Held 100 shares but the flag only manages 58.
        val positions = listOf(pos("TSLA", "STK", qty = 100))
        val orphans = detector.detect(emptyList(), flags, positions)
        assertEquals(1, orphans.size)
        assertTrue(orphans[0].reason.contains("expected 58, held 100"))
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
        val orphans = detector.detect(spreads, emptyList(), positions)
        assertEquals(1, orphans.size)
        assertEquals(-18, orphans[0].position.quantity.toInt())
        assertTrue(orphans[0].reason.contains("expected -1, held -18"))
    }

    @Test
    fun `zero-quantity positions are ignored`() {
        val orphans = detector.detect(emptyList(), emptyList(), listOf(pos("AMD", "OPT", qty = 0, strike = "420", right = "P")))
        assertEquals(0, orphans.size)
    }

    // -------------------------------------------------------------------------
    // Missing legs — the reverse check (COHR 280/270 incident, 2026-07-10)
    // -------------------------------------------------------------------------

    @Test
    fun `a spread leg entirely absent from the account is reported missing`() {
        // The COHR case: engine manages short 420 + long 415, broker holds ONLY the short.
        // detect() cannot see this (it iterates held positions); detectMissing() must.
        val spreads = listOf(openSpread(sold = "420", bought = "415"))
        val positions = listOf(pos("AMD", "OPT", qty = -1, strike = "420", right = "P"))

        assertEquals(0, detector.detect(spreads, emptyList(), positions).size, "no orphans — the short is tracked")
        val missing = detector.detectMissing(spreads, emptyList(), positions)
        assertEquals(1, missing.size)
        assertEquals(1, missing[0].expected)
        assertEquals(0, missing[0].held)
        assertTrue(missing[0].description.contains("415"))
        assertTrue(missing[0].owners.single().contains("420/415"))
    }

    @Test
    fun `a fully-held spread reports nothing missing`() {
        val spreads = listOf(openSpread(sold = "420", bought = "415"))
        val positions =
            listOf(
                pos("AMD", "OPT", qty = -1, strike = "420", right = "P"),
                pos("AMD", "OPT", qty = 1, strike = "415", right = "P"),
            )
        assertEquals(0, detector.detectMissing(spreads, emptyList(), positions).size)
    }

    @Test
    fun `strike scale differences do not produce false missing legs`() {
        // DB strikes are numeric(10,2) ("420.00"), the IBKR feed reports "420" — same contract.
        val spreads = listOf(openSpread(sold = "420.00", bought = "415.00"))
        val positions =
            listOf(
                pos("AMD", "OPT", qty = -1, strike = "420", right = "P"),
                pos("AMD", "OPT", qty = 1, strike = "415", right = "P"),
            )
        assertEquals(0, detector.detectMissing(spreads, emptyList(), positions).size)
        assertEquals(0, detector.detect(spreads, emptyList(), positions).size)
    }

    @Test
    fun `flag stock not held at the broker is reported missing`() {
        val flags = listOf(openFlag("TSLA", shares = 58))
        val missing = detector.detectMissing(emptyList(), flags, listOf(pos("AMD", "STK", qty = 5)))
        assertEquals(1, missing.size)
        assertEquals(58, missing[0].expected)
        assertEquals(0, missing[0].held)
        assertTrue(missing[0].description.contains("TSLA"))
    }

    @Test
    fun `partial shortfall against a managed spread is reported with held and expected quantities`() {
        val spreads = listOf(openSpread(sold = "420", bought = "415", qty = 2))
        val positions =
            listOf(
                pos("AMD", "OPT", qty = -2, strike = "420", right = "P"),
                pos("AMD", "OPT", qty = 1, strike = "415", right = "P"),
            )
        val missing = detector.detectMissing(spreads, emptyList(), positions)
        assertEquals(1, missing.size)
        assertEquals(2, missing[0].expected)
        assertEquals(1, missing[0].held)
    }
}
