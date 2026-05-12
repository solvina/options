package cz.solvina.options.domain.features.market

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * European put option pricing via the Black-Scholes-Merton model.
 *
 * Conventions:
 *   spot   — underlying spot price
 *   strike — strike price
 *   t      — time to expiry in years  (e.g. 45 days = 45.0 / 365.0)
 *   r      — continuously-compounded risk-free rate (e.g. 0.05 = 5 %)
 *   sigma  — annualised implied volatility (e.g. 0.20 = 20 %)
 *
 * Greeks sign conventions:
 *   delta — negative for puts (range −1 … 0)
 *   gamma — positive
 *   theta — negative (time-decay per calendar day)
 *   vega  — positive, per 1-point (100 %) move in vol; divide by 100 for per-1 % figure
 */
object BlackScholes {
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

    private fun d1d2(
        spot: Double,
        strike: Double,
        t: Double,
        r: Double,
        sigma: Double,
    ): Pair<Double, Double> {
        val d1 = (ln(spot / strike) + (r + sigma * sigma / 2.0) * t) / (sigma * sqrt(t))
        return d1 to (d1 - sigma * sqrt(t))
    }

    private fun nd(x: Double): Double = exp(-x * x / 2.0) / sqrt(2.0 * PI)

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
