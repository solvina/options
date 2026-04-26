package cz.solvina.options.backtest

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * European put option pricing via the Black-Scholes-Merton model.
 *
 * Conventions used throughout:
 *   spot  — underlying spot price
 *   strike — strike price
 *   t     — time to expiry in years  (e.g. 45 days = 45.0 / 365.0)
 *   r     — continuously-compounded risk-free rate (e.g. 0.05 = 5 %)
 *   sigma — annualised implied volatility (e.g. 0.20 = 20 %)
 *
 * All greeks follow the standard sign conventions:
 *   delta  — negative for puts (range −1 … 0)
 *   gamma  — positive
 *   theta  — negative (time-decay per calendar day)
 *   vega   — positive, expressed per 1-point (100 %) move in vol;
 *             divide by 100 to get the standard "per 1 %" figure
 */
object BlackScholes {
    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Fair value of a European put. */
    fun putPrice(
        spot: Double,
        strike: Double,
        t: Double,
        r: Double,
        sigma: Double,
    ): Double {
        if (t <= 0.0) return maxOf(strike - spot, 0.0)
        val (d1, d2) = d1d2(spot, strike, t, r, sigma)
        return strike * exp(-r * t) * cnd(-d2) - spot * cnd(-d1)
    }

    /** Delta of a European put (always ≤ 0). */
    fun putDelta(
        spot: Double,
        strike: Double,
        t: Double,
        r: Double,
        sigma: Double,
    ): Double {
        if (t <= 0.0) return if (spot < strike) -1.0 else 0.0
        val (d1, _) = d1d2(spot, strike, t, r, sigma)
        return cnd(d1) - 1.0
    }

    /** Gamma (identical for put and call). */
    fun gamma(
        spot: Double,
        strike: Double,
        t: Double,
        r: Double,
        sigma: Double,
    ): Double {
        if (t <= 0.0) return 0.0
        val (d1, _) = d1d2(spot, strike, t, r, sigma)
        return nd(d1) / (spot * sigma * sqrt(t))
    }

    /**
     * Theta of a European put in $ per calendar day.
     * (Standard textbook formula divided by 365.)
     */
    fun putTheta(
        spot: Double,
        strike: Double,
        t: Double,
        r: Double,
        sigma: Double,
    ): Double {
        if (t <= 0.0) return 0.0
        val (d1, d2) = d1d2(spot, strike, t, r, sigma)
        val term1 = -spot * nd(d1) * sigma / (2.0 * sqrt(t))
        val term2 = r * strike * exp(-r * t) * cnd(-d2)
        return (term1 + term2) / 365.0
    }

    /**
     * Vega of a European put per 1-point (100 %) change in sigma.
     * Divide by 100 for the conventional "per 1 % vol" figure.
     */
    fun vega(
        spot: Double,
        strike: Double,
        t: Double,
        r: Double,
        sigma: Double,
    ): Double {
        if (t <= 0.0) return 0.0
        val (d1, _) = d1d2(spot, strike, t, r, sigma)
        return spot * nd(d1) * sqrt(t)
    }

    /**
     * Implied volatility from a market put price via Newton-Raphson iteration.
     *
     * Returns `null` if the price is outside the no-arbitrage bounds, or if the
     * solver does not converge within [maxIter] steps.
     */
    fun impliedVol(
        marketPrice: Double,
        spot: Double,
        strike: Double,
        t: Double,
        r: Double,
        initialGuess: Double = 0.30,
        maxIter: Int = 100,
        tolerance: Double = 1e-6,
    ): Double? {
        if (t <= 0.0) return null
        val intrinsic = maxOf(strike * exp(-r * t) - spot, 0.0)
        if (marketPrice < intrinsic - tolerance) return null
        if (marketPrice >= spot) return null

        var sigma = initialGuess
        repeat(maxIter) {
            val price = putPrice(spot, strike, t, r, sigma)
            val v = vega(spot, strike, t, r, sigma)
            if (abs(v) < 1e-10) return null
            val diff = price - marketPrice
            if (abs(diff) < tolerance) return sigma
            sigma -= diff / v
            if (sigma <= 0.0) sigma = tolerance
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun d1d2(
        spot: Double,
        strike: Double,
        t: Double,
        r: Double,
        sigma: Double,
    ): Pair<Double, Double> {
        val d1 = (ln(spot / strike) + (r + sigma * sigma / 2.0) * t) / (sigma * sqrt(t))
        val d2 = d1 - sigma * sqrt(t)
        return d1 to d2
    }

    /** Standard-normal PDF. */
    private fun nd(x: Double): Double = exp(-x * x / 2.0) / sqrt(2.0 * PI)

    /**
     * Cumulative standard-normal CDF via Hart's rational approximation
     * (accurate to ~7.5 significant digits for |x| ≤ 7.65).
     */
    private fun cnd(x: Double): Double {
        if (x < -7.65) return 0.0
        if (x > 7.65) return 1.0
        val t = 1.0 / (1.0 + 0.2316419 * abs(x))
        val poly =
            t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        val approx = 1.0 - nd(x) * poly
        return if (x >= 0.0) approx else 1.0 - approx
    }
}
