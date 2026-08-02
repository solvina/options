package cz.solvina.options.domain.features.market.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Bid/ask for a two-leg spread quoted as a single instrument (an IBKR BAG), as opposed to the
 * synthetic `soldBid − boughtAsk` natural cross the scanner derived from the individual legs.
 *
 * The natural cross assumes both legs are hit at their worst posted price simultaneously — a price
 * the engine is configured never to accept anyway (`entry-min-fill-pct-of-mid` floors every entry at
 * 95% of fresh mid). In July that test rejected 2602 of 3028 evaluated spreads on a number nobody
 * would ever have traded at.
 */
data class ComboQuote(
    val bid: BigDecimal,
    val ask: BigDecimal,
) {
    /**
     * The achievable credit implied by this combo bid, or null when the quote cannot be trusted.
     *
     * IBKR's sign convention for a SELL+BUY package is not documented in the vendored TWS jar (javap
     * yields signatures, not semantics) and this engine has never requested BAG market data before,
     * so there is no local precedent to read it off. Rather than guess, both orientations are tested
     * against the leg-derived [referenceMid] — which is independently reliable — and the one landing
     * in a plausible band around it wins. A quote consistent with neither orientation is discarded so
     * the caller falls back to the natural cross.
     *
     * Deliberately rejects an exact −1.0: that is IBKR's "no cached quote yet" placeholder, and for a
     * combo (where a negative bid may be legitimate) it is otherwise indistinguishable from real data.
     */
    fun achievableCredit(referenceMid: BigDecimal): BigDecimal? {
        if (referenceMid <= BigDecimal.ZERO) return null
        if (bid.compareTo(PLACEHOLDER) == 0) return null
        val lo = referenceMid.multiply(BAND_LOW)
        val hi = referenceMid.multiply(BAND_HIGH)
        return listOf(bid, bid.negate())
            .firstOrNull { it >= lo && it <= hi }
            ?.setScale(4, RoundingMode.HALF_UP)
    }

    private companion object {
        val PLACEHOLDER: BigDecimal = BigDecimal("-1")
        val BAND_LOW: BigDecimal = BigDecimal("0.25")
        val BAND_HIGH: BigDecimal = BigDecimal("2.0")
    }
}
