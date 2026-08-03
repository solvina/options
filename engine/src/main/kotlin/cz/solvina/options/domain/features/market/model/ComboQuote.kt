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
     * The credit achievable right now on this combo, or null when the quote cannot be trusted.
     *
     * **Read the ASK, negated.** The BAG is built as SELL(sold leg) + BUY(bought leg), so opening the
     * spread means *buying* that package — and a buyer transacts at the ask. IBKR prices a
     * credit-bearing package negatively (you receive money), so `−ask` is the credit. The bid is the
     * other side of the book, what it would cost to *close* the package, and is not what an entry
     * can achieve; reading the entry credit off it produced floors above the leg-derived mid
     * (HON 2026-08-03: bid −3.05 against a $2.175 mid), which no order could ever fill at.
     *
     * Convention confirmed against the first live session, 2026-08-03 15:30 CEST:
     *
     * | symbol | bag_bid | bag_ask | −ask | leg natural cross |
     * |--------|---------|---------|------|-------------------|
     * | HON    | −3.05   | −0.60   | 0.60 | 0.30              |
     * | TMUS   | −2.70   | −0.50   | 0.50 | 0.30              |
     * | ICE    | −1.90   | +0.05   | −0.05| −0.05             |
     *
     * ICE pins it: with no credit available on either measure the two agree exactly. HON and TMUS
     * show the point of quoting the combo at all — the real combo book beat the synthetic natural
     * cross by $0.20–0.30/share, i.e. $20–30 per spread.
     *
     * A negative result is a legitimate answer ("this spread pays nothing"), returned rather than
     * nulled so the caller rejects on a real number instead of falling back.
     *
     * Guards: an exact −1.0 ask is IBKR's "no cached quote yet" placeholder, indistinguishable from a
     * real quote for a combo where negative prices are normal. A credit implausibly far above
     * [referenceMid] means the leg mid and the combo quote disagree beyond what book skew explains —
     * the ask side is the worse side, so it should sit below mid — and is discarded rather than
     * trusted.
     */
    fun achievableCredit(referenceMid: BigDecimal): BigDecimal? {
        if (referenceMid <= BigDecimal.ZERO) return null
        if (ask.compareTo(PLACEHOLDER) == 0) return null
        val credit = ask.negate().setScale(4, RoundingMode.HALF_UP)
        if (credit > referenceMid.multiply(MAX_PLAUSIBLE_VS_MID)) return null
        return credit
    }

    private companion object {
        val PLACEHOLDER: BigDecimal = BigDecimal("-1")

        /** Slack over the leg-derived mid before a combo quote is treated as inconsistent, not tight. */
        val MAX_PLAUSIBLE_VS_MID: BigDecimal = BigDecimal("1.25")
    }
}
