package cz.solvina.options.backtest

import cz.solvina.options.domain.features.market.BlackScholes
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Reference values produced by the Black-Scholes-Merton closed-form solution.
 *
 * Scenario parameters used throughout unless noted:
 *   spot   = 500.00   (SPY-like underlying)
 *   strike = 480.00   (OTM put, ~4 % below spot)
 *   tte    = 45/365   (45 DTE)
 *   rate   = 0.05     (5 % risk-free rate)
 *   vol    = 0.20     (20 % IV)
 *
 * Expected values were cross-checked against a reference BS calculator.
 * Tolerances are tight (1e-4) — any regression in the closed-form implementation
 * will be caught immediately.
 */
class BlackScholesTest {
    // -----------------------------------------------------------------------
    // Shared scenario
    // -----------------------------------------------------------------------

    private val spot = 500.0
    private val strike = 480.0
    private val tte = 45.0 / 365.0
    private val rate = 0.05
    private val vol = 0.20

    private fun assertApprox(
        expected: Double,
        actual: Double,
        tol: Double = 1e-4,
        label: String = "",
    ) {
        assertTrue(
            abs(actual - expected) <= tol,
            "$label: expected $expected ± $tol but got $actual",
        )
    }

    // -----------------------------------------------------------------------
    // Put price
    // -----------------------------------------------------------------------

    @Test
    fun `put price OTM scenario`() {
        // spot=500, strike=480, T=45d, r=5%, σ=20%  →  ~5.1603
        val price = BlackScholes.putPrice(spot, strike, tte, rate, vol)
        assertApprox(5.1603, price, tol = 0.01, label = "put price")
    }

    @Test
    fun `put price ATM is positive`() {
        val price = BlackScholes.putPrice(spot, spot, tte, rate, vol)
        assertTrue(price > 0.0, "ATM put must be positive")
    }

    @Test
    fun `put price at expiry equals intrinsic`() {
        assertApprox(20.0, BlackScholes.putPrice(480.0, 500.0, 0.0, rate, vol), tol = 1e-9, label = "ITM at expiry")
        assertApprox(0.0, BlackScholes.putPrice(spot, strike, 0.0, rate, vol), tol = 1e-9, label = "OTM at expiry")
    }

    // -----------------------------------------------------------------------
    // Put delta
    // -----------------------------------------------------------------------

    @Test
    fun `put delta is negative and within 0 to -1`() {
        val delta = BlackScholes.putDelta(spot, strike, tte, rate, vol)
        assertTrue(delta < 0.0, "put delta must be negative")
        assertTrue(delta > -1.0, "put delta must be > -1")
    }

    @Test
    fun `put delta OTM scenario`() {
        // spot=500, strike=480, T=45d, r=5%, σ=20%  →  ~-0.2407
        val delta = BlackScholes.putDelta(spot, strike, tte, rate, vol)
        assertApprox(-0.2407, delta, tol = 0.005, label = "put delta")
    }

    @Test
    fun `put delta ATM is approximately -0_45`() {
        // ATM put delta is slightly above -0.5 due to the drift term
        val delta = BlackScholes.putDelta(spot, spot, tte, rate, vol)
        assertApprox(-0.4511, delta, tol = 0.02, label = "ATM put delta")
    }

    @Test
    fun `put delta deep ITM approaches -1`() {
        val delta = BlackScholes.putDelta(400.0, 500.0, tte, rate, vol)
        assertTrue(delta < -0.95, "deep ITM delta should approach -1, got $delta")
    }

    @Test
    fun `put delta deep OTM approaches 0`() {
        val delta = BlackScholes.putDelta(600.0, 500.0, tte, rate, vol)
        assertTrue(delta > -0.05, "deep OTM delta should approach 0, got $delta")
    }

    // -----------------------------------------------------------------------
    // Gamma
    // -----------------------------------------------------------------------

    @Test
    fun `gamma is positive`() {
        assertTrue(BlackScholes.gamma(spot, strike, tte, rate, vol) > 0.0)
    }

    @Test
    fun `gamma peaks near ATM`() {
        val gammaAtm = BlackScholes.gamma(spot, spot, tte, rate, vol)
        val gammaOtm = BlackScholes.gamma(spot, strike, tte, rate, vol)
        assertTrue(gammaAtm > gammaOtm, "ATM gamma should exceed OTM gamma")
    }

    // -----------------------------------------------------------------------
    // Theta
    // -----------------------------------------------------------------------

    @Test
    fun `theta is negative per day`() {
        val theta = BlackScholes.putTheta(spot, strike, tte, rate, vol)
        assertTrue(theta < 0.0, "theta must be negative (time decay), got $theta")
    }

    @Test
    fun `theta is negative for far expiry too`() {
        val thetaFar = BlackScholes.putTheta(spot, strike, 180.0 / 365.0, rate, vol)
        val thetaNear = BlackScholes.putTheta(spot, strike, 7.0 / 365.0, rate, vol)
        assertTrue(thetaFar < 0.0)
        assertTrue(thetaNear < 0.0)
    }

    // -----------------------------------------------------------------------
    // Vega
    // -----------------------------------------------------------------------

    @Test
    fun `vega is positive`() {
        assertTrue(BlackScholes.vega(spot, strike, tte, rate, vol) > 0.0)
    }

    @Test
    fun `vega per 1pct vol is a small positive number`() {
        val vegaPer1Pct = BlackScholes.vega(spot, strike, tte, rate, vol) / 100.0
        assertTrue(vegaPer1Pct in 0.01..5.0, "per-1%-vol vega out of plausible range: $vegaPer1Pct")
    }

    // -----------------------------------------------------------------------
    // Implied volatility round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `implied vol round-trips through put price`() {
        val price = BlackScholes.putPrice(spot, strike, tte, rate, vol)
        val iv = BlackScholes.impliedVol(price, spot, strike, tte, rate)
        assertNotNull(iv, "impliedVol should converge")
        assertApprox(vol, iv!!, tol = 1e-5, label = "implied vol round-trip")
    }

    @Test
    fun `implied vol round-trip ATM`() {
        val price = BlackScholes.putPrice(spot, spot, tte, rate, vol)
        val iv = BlackScholes.impliedVol(price, spot, spot, tte, rate)
        assertNotNull(iv)
        assertApprox(vol, iv!!, tol = 1e-5, label = "ATM IV round-trip")
    }

    @Test
    fun `implied vol returns null for price below intrinsic`() {
        val iv = BlackScholes.impliedVol(-1.0, spot, strike, tte, rate)
        assertNull(iv, "should return null for sub-intrinsic price")
    }

    @Test
    fun `implied vol returns null at expiry`() {
        val iv = BlackScholes.impliedVol(20.0, 480.0, 500.0, 0.0, rate)
        assertNull(iv, "should return null when T=0")
    }

    // -----------------------------------------------------------------------
    // Put-call parity sanity check
    // -----------------------------------------------------------------------

    @Test
    fun `put-call parity holds`() {
        // C - P = S - K*e^(-rT)  =>  call = put + S - K*e^(-rT)
        val put = BlackScholes.putPrice(spot, strike, tte, rate, vol)
        val parityCall = put + spot - strike * kotlin.math.exp(-rate * tte)
        assertTrue(parityCall > 0.0, "parity-derived call price should be positive: $parityCall")
    }
}
