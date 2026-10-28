package cz.solvina.options.notification

import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.notification.SpreadOpportunity
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionGreeks
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The CRM 265/280 bear call email of 2026-10-21, as numbers. */
class SpreadOpportunityTest {
    private val expiry = LocalDate.of(2026, 11, 27)

    private fun call(
        strike: String,
        bid: String,
        ask: String,
        iv: Double,
    ) = OptionQuote(
        contract = OptionContract(Symbol("CRM"), expiry, BigDecimal(strike), OptionType.CALL),
        bid = Money(BigDecimal(bid)),
        ask = Money(BigDecimal(ask)),
        mid = Money(BigDecimal(bid).add(BigDecimal(ask)).divide(BigDecimal(2))),
        greeks = OptionGreeks(delta = 0.2, gamma = 0.01, theta = -0.1, vega = 0.2, iv = iv),
    )

    private fun crm(
        underlyingStopEnabled: Boolean = true,
        bufferPct: Double = 0.0,
    ) = SpreadOpportunity(
        strategyId = StrategyId.BEAR_CALL,
        symbol = Symbol("CRM"),
        session = "US",
        detectedAt = Instant.parse("2026-10-21T17:46:55Z"),
        ivRank = 46.0,
        ivRankThreshold = 45.0,
        expiry = expiry,
        dte = 37,
        minDte = 30,
        maxDte = 50,
        preferredDte = 45,
        targetDelta = 0.30,
        deltaMin = 0.25,
        deltaMax = 0.30,
        underlyingPrice = BigDecimal("242.45"),
        shortLeg = call("265", "3.90", "6.05", 0.394),
        longLeg = call("280", "1.83", "2.91", 0.399),
        midCredit = BigDecimal("2.61"),
        bidCredit = BigDecimal("1.20"),
        minCreditPerShare = BigDecimal("0.90"),
        targetWidth = BigDecimal("5"),
        actualWidth = BigDecimal("15"),
        maxRiskPerShare = BigDecimal("12.39"),
        totalCapital = BigDecimal("50000"),
        maxRiskPercent = 0.025,
        quantity = 1,
        takeProfitPercent = 0.5,
        stopLossPercent = 0.0,
        underlyingStopEnabled = underlyingStopEnabled,
        underlyingStopBufferPct = bufferPct,
        timeProfitDte = 21,
        driftProtectionPct = 0.05,
    )

    @Test
    fun `bear call stop triggers at the short strike, moved below it by the buffer`() {
        assertEquals(BigDecimal("265.00"), crm().underlyingStopTrigger)
        assertEquals(BigDecimal("259.70"), crm(bufferPct = 0.02).underlyingStopTrigger)
        assertNull(crm(underlyingStopEnabled = false).underlyingStopTrigger)
    }

    @Test
    fun `fair value comes from the legs' own volatility and the suggested limit is never below mid`() {
        val o = crm()
        val fair = assertNotNull(o.fairCredit)

        assertTrue(fair > BigDecimal.ZERO && fair < BigDecimal("15"), "fair=$fair")
        assertTrue(o.suggestedLimit >= o.midCredit, "suggested=${o.suggestedLimit}")
        assertEquals(0, o.suggestedLimit.remainder(BigDecimal("0.05")).signum(), "rounded to a nickel")
    }
}
