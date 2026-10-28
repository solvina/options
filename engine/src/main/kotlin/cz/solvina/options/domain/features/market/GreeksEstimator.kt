package cz.solvina.options.domain.features.market

import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.models.OptionGreeks
import kotlin.math.abs

/**
 * Fills in greeks for chain strikes that came back with a real two-sided quote but no
 * option-computation tick from IBKR (delta NaN). Without this such strikes were dropped outright —
 * including as the long leg, which needs no greeks at all — and the selector silently widened the
 * spread past them (QCOM 145/130 on 2026-08-04, CRM 265/280 on 2026-10-21, ORCL 118/110 on
 * 2026-10-28 where 115 had bid 3.00 / ask 3.30 and no delta).
 *
 * Only ever estimates FROM real data: the volatility comes from live neighbouring strikes, and the
 * strike's own mid is trusted only when it agrees with them. With no live neighbour the quote is
 * left un-estimated (still usable as a long leg, never as a short).
 */
object GreeksEstimator {
    // Delta of a 30–50 DTE option moves ~0.001 per 1% of rate; a fixed rate is well inside the noise.
    const val RISK_FREE_RATE = 0.04

    // A wide market's mid can imply a volatility far from the smile; beyond this relative gap the
    // neighbours' interpolated volatility is used instead of the strike's own.
    const val MAX_OWN_IV_DEVIATION = 0.25

    fun hasLiveGreeks(quote: OptionQuote): Boolean = !quote.greeks.delta.isNaN() && !quote.greeksEstimated

    /**
     * @param t time to expiry in years.
     */
    fun fillMissing(
        quotes: List<OptionQuote>,
        spot: Double,
        t: Double,
    ): List<OptionQuote> {
        val live = quotes.filter { hasLiveGreeks(it) && it.greeks.iv > 0.0 }
        return quotes.map { quote ->
            if (!quote.greeks.delta.isNaN()) return@map quote
            val sigma = volatilityFor(quote, live, spot, t) ?: return@map quote
            val type = quote.contract.type
            val k = quote.contract.strike.toDouble()
            quote.copy(
                greeks =
                    OptionGreeks(
                        delta = BlackScholes.delta(type, spot, k, t, RISK_FREE_RATE, sigma),
                        gamma = BlackScholes.gamma(spot, k, t, RISK_FREE_RATE, sigma),
                        theta = BlackScholes.theta(type, spot, k, t, RISK_FREE_RATE, sigma),
                        // IBKR reports vega per 1 vol point; BlackScholes.vega is per 100.
                        vega = BlackScholes.vega(spot, k, t, RISK_FREE_RATE, sigma) / 100.0,
                        iv = sigma,
                    ),
                greeksEstimated = true,
            )
        }
    }

    private fun volatilityFor(
        quote: OptionQuote,
        live: List<OptionQuote>,
        spot: Double,
        t: Double,
    ): Double? {
        val neighbourIv = neighbourIv(quote, live) ?: return null
        val bid = quote.bid.amount.toDouble()
        val ask = quote.ask.amount.toDouble()
        val ownIv =
            if (bid > 0.0 && ask >= bid) {
                BlackScholes.impliedVol(
                    quote.contract.type,
                    (bid + ask) / 2.0,
                    spot,
                    quote.contract.strike.toDouble(),
                    t,
                    RISK_FREE_RATE,
                )
            } else {
                null
            }
        return if (ownIv != null && abs(ownIv - neighbourIv) / neighbourIv <= MAX_OWN_IV_DEVIATION) ownIv else neighbourIv
    }

    /** Volatility interpolated linearly in strike between the nearest live strikes on each side. */
    private fun neighbourIv(
        quote: OptionQuote,
        live: List<OptionQuote>,
    ): Double? {
        val k = quote.contract.strike
        val below = live.filter { it.contract.strike < k }.maxByOrNull { it.contract.strike }
        val above = live.filter { it.contract.strike > k }.minByOrNull { it.contract.strike }
        return when {
            below != null && above != null -> {
                val k0 = below.contract.strike.toDouble()
                val k1 = above.contract.strike.toDouble()
                val w = (k.toDouble() - k0) / (k1 - k0)
                below.greeks.iv + w * (above.greeks.iv - below.greeks.iv)
            }
            below != null -> below.greeks.iv
            above != null -> above.greeks.iv
            else -> null
        }
    }
}
