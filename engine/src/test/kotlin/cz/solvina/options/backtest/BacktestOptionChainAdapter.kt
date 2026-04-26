package cz.solvina.options.backtest

import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionGreeks
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Synthetic option chain for backtesting.
 *
 * Expirations: standard monthly options expiry (3rd Friday of each month),
 * for the next 12 months from the simulated clock date.
 *
 * Pricing: Black-Scholes with flat IV surface. The sigma input is the most
 * recent OPTION_IMPLIED_VOLATILITY bar on or before clock.currentDate(),
 * loaded from the IV fixture CSV (the same data the live adapter fetches
 * from IBKR reqHistoricalData). This keeps the model honest — it only sees
 * historical IV that would have been available on the simulated day.
 *
 * Strike grid: [candidateStrikeCount] OTM puts below spot, spaced by
 * [strikeStep], starting from the first $5 multiple at or below spot.
 *
 * Bid/ask model: mid ± max(5 % × mid, $0.05), reflecting the wider
 * market-making spread typical for lower-volume expirations. Mark-to-market
 * during position monitoring uses getOptionMid on BacktestMarketDataAdapter,
 * which uses the same flat-IV BS formula.
 *
 * Risk-free rate: 5 % (hardcoded; realistic for a US rate environment and
 * immaterial for short-DTE OTM puts).
 */
class BacktestOptionChainAdapter(
    private val clock: MutableClock,
    private val config: ScannerConfig,
    private val strikeStep: Double = 5.0,
    private val riskFreeRate: Double = 0.05,
) : OptionChainPort {
    private val ivCache = mutableMapOf<Symbol, List<cz.solvina.options.domain.models.HistoricalBar>>()

    override suspend fun getAvailableExpirations(symbol: Symbol): Set<LocalDate> {
        val today = clock.currentDate()
        return (0..11)
            .map { offset -> thirdFriday(YearMonth.from(today).plusMonths(offset.toLong())) }
            .filter { it > today }
            .toSet()
    }

    override suspend fun getOptionChain(
        symbol: Symbol,
        expiry: LocalDate,
        underlyingPrice: Money,
    ): List<OptionQuote> {
        val today = clock.currentDate()
        val spot = underlyingPrice.amount.toDouble()
        val tte = ChronoUnit.DAYS.between(today, expiry) / 365.0
        val sigma = currentIv(symbol, today)

        return generateStrikes(spot)
            .map { strikeDouble ->
                val strike = BigDecimal(strikeDouble).setScale(2, RoundingMode.HALF_UP)
                buildQuote(symbol, expiry, strike, strikeDouble, spot, tte, sigma)
            }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun currentIv(
        symbol: Symbol,
        today: LocalDate,
    ): Double {
        val bars = ivCache.getOrPut(symbol) { FixtureLoader.loadIvBars(symbol) }
        return bars
            .lastOrNull { it.date <= today }
            ?.iv
            ?: 0.20 // 20 % fallback when no fixture data precedes the start date
    }

    private fun generateStrikes(spot: Double): List<Double> {
        val lowestStep = (spot / strikeStep).toLong() * strikeStep
        return (0 until config.candidateStrikeCount)
            .map { i -> lowestStep - i * strikeStep }
            .filter { it > 0.0 }
    }

    private fun buildQuote(
        symbol: Symbol,
        expiry: LocalDate,
        strike: BigDecimal,
        strikeDouble: Double,
        spot: Double,
        tte: Double,
        sigma: Double,
    ): OptionQuote {
        val mid = BlackScholes.putPrice(spot, strikeDouble, tte, riskFreeRate, sigma)
        val halfSpread = maxOf(mid * 0.05, 0.05)
        val bid = maxOf(mid - halfSpread, 0.01)
        val ask = mid + halfSpread

        return OptionQuote(
            contract =
                OptionContract(
                    symbol = symbol,
                    expiry = expiry,
                    strike = strike,
                    type = OptionType.PUT,
                ),
            bid = Money(BigDecimal(bid).setScale(2, RoundingMode.HALF_UP)),
            ask = Money(BigDecimal(ask).setScale(2, RoundingMode.HALF_UP)),
            mid = Money(BigDecimal(mid).setScale(4, RoundingMode.HALF_UP)),
            greeks =
                OptionGreeks(
                    delta = BlackScholes.putDelta(spot, strikeDouble, tte, riskFreeRate, sigma),
                    gamma = BlackScholes.gamma(spot, strikeDouble, tte, riskFreeRate, sigma),
                    theta = BlackScholes.putTheta(spot, strikeDouble, tte, riskFreeRate, sigma),
                    vega = BlackScholes.vega(spot, strikeDouble, tte, riskFreeRate, sigma) / 100.0,
                    iv = sigma,
                ),
        )
    }

    private fun thirdFriday(yearMonth: YearMonth): LocalDate =
        yearMonth
            .atDay(1)
            .with(TemporalAdjusters.firstInMonth(DayOfWeek.FRIDAY))
            .plusWeeks(2)
}
