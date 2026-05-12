package cz.solvina.options.backtest

import cz.solvina.options.domain.features.market.BlackScholes
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.models.HistoricalBar
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.temporal.ChronoUnit

/**
 * Serves market data from fixture CSVs for the simulated clock date.
 *
 * getUnderlyingPrice: returns the closing price on clock.currentDate(),
 * or the most recent prior close if that exact date has no bar (weekend /
 * market holiday).
 *
 * getOptionMid: prices the contract via Black-Scholes using a flat IV
 * surface (most recent IV fixture bar for the underlying) and the spot
 * price from the price fixture. Handles both puts and calls; only puts
 * are used by the current strategy but the call formula is included for
 * completeness (via put-call parity).
 */
class BacktestMarketDataAdapter(
    private val clock: MutableClock,
    private val riskFreeRate: Double = 0.05,
) : MarketDataPort {
    private val priceCache = mutableMapOf<Symbol, List<HistoricalBar>>()
    private val ivCache = mutableMapOf<Symbol, List<HistoricalBar>>()

    override suspend fun getUnderlyingPrice(symbol: Symbol): Money {
        val bars = priceCache.getOrPut(symbol) { FixtureLoader.loadPriceBars(symbol) }
        val today = clock.currentDate()
        val bar =
            bars.lastOrNull { it.date <= today }
                ?: error("No price data for $symbol on or before $today — fixture may not cover this date")
        return Money(bar.close)
    }

    override suspend fun getOptionMid(contract: OptionContract): Money {
        val today = clock.currentDate()
        val spot = getUnderlyingPrice(contract.symbol).amount.toDouble()
        val strikeDouble = contract.strike.toDouble()
        val tte = ChronoUnit.DAYS.between(today, contract.expiry) / 365.0
        if (tte <= 0.0) {
            val intrinsic =
                when (contract.type) {
                    OptionType.PUT -> maxOf(strikeDouble - spot, 0.0)
                    OptionType.CALL -> maxOf(spot - strikeDouble, 0.0)
                }
            return Money(BigDecimal(intrinsic).setScale(2, RoundingMode.HALF_UP))
        }
        val sigma = currentIv(contract.symbol, today)
        val mid =
            when (contract.type) {
                OptionType.PUT -> BlackScholes.putPrice(spot, strikeDouble, tte, riskFreeRate, sigma)
                // call via put-call parity: C = P + S - K·e^(-rT)
                OptionType.CALL -> {
                    val put = BlackScholes.putPrice(spot, strikeDouble, tte, riskFreeRate, sigma)
                    put + spot - strikeDouble * kotlin.math.exp(-riskFreeRate * tte)
                }
            }
        return Money(BigDecimal(maxOf(mid, 0.0)).setScale(4, RoundingMode.HALF_UP))
    }

    // -------------------------------------------------------------------------
    // Package-visible helpers (used by backtest adapters in the same package)
    // -------------------------------------------------------------------------

    fun getIv(symbol: Symbol): Double = currentIv(symbol, clock.currentDate())

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun currentIv(
        symbol: Symbol,
        today: java.time.LocalDate,
    ): Double {
        val bars = ivCache.getOrPut(symbol) { FixtureLoader.loadIvBars(symbol) }
        return bars
            .lastOrNull { it.date <= today }
            ?.iv
            ?: 0.20
    }
}
