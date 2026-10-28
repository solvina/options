package cz.solvina.options.market

import cz.solvina.options.domain.features.market.BlackScholes
import cz.solvina.options.domain.features.market.GreeksEstimator
import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionGreeks
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GreeksEstimatorTest {
    private val spot = 100.0
    private val t = 40 / 365.0
    private val r = GreeksEstimator.RISK_FREE_RATE

    private fun quote(
        type: OptionType,
        strike: Double,
        iv: Double,
        live: Boolean,
        halfSpread: Double = 0.05,
    ): OptionQuote {
        val price = BlackScholes.price(type, spot, strike, t, r, iv)
        val greeks =
            if (live) {
                OptionGreeks(BlackScholes.delta(type, spot, strike, t, r, iv), 0.0, 0.0, 0.0, iv)
            } else {
                OptionGreeks(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
            }
        return OptionQuote(
            contract = OptionContract(Symbol("X"), LocalDate.of(2026, 12, 4), BigDecimal(strike), type),
            bid = Money(BigDecimal(price - halfSpread)),
            ask = Money(BigDecimal(price + halfSpread)),
            mid = Money(BigDecimal(price)),
            greeks = greeks,
        )
    }

    @Test
    fun `a put without greeks between live neighbours gets a delta between theirs`() {
        val quotes =
            listOf(
                quote(OptionType.PUT, 90.0, 0.32, live = true),
                quote(OptionType.PUT, 95.0, 0.31, live = false),
                quote(OptionType.PUT, 100.0, 0.30, live = true),
            )

        val filled = GreeksEstimator.fillMissing(quotes, spot, t)[1]

        assertTrue(filled.greeksEstimated)
        assertTrue(filled.greeks.delta < quotes[0].greeks.delta && filled.greeks.delta > quotes[2].greeks.delta)
        assertTrue(abs(filled.greeks.iv - 0.31) < 0.01, "own mid should recover ~31% vol, was ${filled.greeks.iv}")
    }

    @Test
    fun `a call is estimated with a positive delta`() {
        val quotes =
            listOf(
                quote(OptionType.CALL, 105.0, 0.30, live = true),
                quote(OptionType.CALL, 110.0, 0.30, live = false),
                quote(OptionType.CALL, 115.0, 0.30, live = true),
            )

        val filled = GreeksEstimator.fillMissing(quotes, spot, t)[1]

        assertTrue(filled.greeks.delta > quotes[2].greeks.delta && filled.greeks.delta < quotes[0].greeks.delta)
    }

    @Test
    fun `a wide market whose mid disagrees with the smile falls back to the neighbours' volatility`() {
        val wide = quote(OptionType.PUT, 95.0, 0.60, live = false) // mid priced at 60% vol
        val quotes = listOf(quote(OptionType.PUT, 90.0, 0.30, live = true), wide, quote(OptionType.PUT, 100.0, 0.30, live = true))

        val filled = GreeksEstimator.fillMissing(quotes, spot, t)[1]

        assertEquals(0.30, filled.greeks.iv, 1e-9)
    }

    @Test
    fun `with no live neighbour the strike stays unestimated`() {
        val quotes = listOf(quote(OptionType.PUT, 95.0, 0.30, live = false))

        val filled = GreeksEstimator.fillMissing(quotes, spot, t).single()

        assertFalse(filled.greeksEstimated)
        assertTrue(filled.greeks.delta.isNaN())
    }
}
