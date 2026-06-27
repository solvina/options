package cz.solvina.options.domain.features.spread.strategy.bearcall

import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.features.spread.strategy.SpreadStrategy
import cz.solvina.options.domain.features.spread.strategy.StrategyExit
import cz.solvina.options.domain.features.universe.UniversePort
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Bear call strategy. Beyond the shared TP/SL/DTE rules it adds ex-dividend early-assignment
 * protection on the short call: the holder of a short call gets assigned early when the dividend
 * they capture by exercising exceeds the extrinsic value they give up. We force-close before that.
 *
 * Applies to American-style (US) names only — EU/European-style options have no early assignment.
 * Inert until the dividend-data pipeline populates [cz.solvina.options.domain.features.universe.InstrumentConfig.exDividendDate].
 */
@Component
class BearCallStrategy(
    private val marketDataPort: MarketDataPort,
    private val universePort: UniversePort,
    private val config: BearCallScannerConfig,
    private val clock: Clock,
) : SpreadStrategy {
    override val id = StrategyId.BEAR_CALL

    override suspend fun strategyExitSignal(spread: Spread): StrategyExit? {
        // Early assignment only happens for American-style (US) options.
        if (universePort.getMarketSchedule(spread.symbol).session != "US") return null

        val instrument = universePort.get(spread.symbol) ?: return null
        val exDiv = instrument.exDividendDate ?: return null
        val daysToExDiv = ChronoUnit.DAYS.between(LocalDate.now(clock), exDiv)
        val windowDays = config.dividendCheckWindowHours / 24
        if (daysToExDiv < 0 || daysToExDiv > windowDays) return null

        val shortStrike = spread.soldLeg.contract.strike
        val spot = marketDataPort.getUnderlyingPrice(spread.symbol).amount
        if (spot <= shortStrike) return null // short call OTM → no early-assignment incentive

        val dividend = instrument.nextDividendAmount ?: BigDecimal.ZERO
        val intrinsic = (spot - shortStrike).max(BigDecimal.ZERO)
        val extrinsic = marketDataPort.getOptionMidLive(spread.soldLeg.contract)?.amount?.let { it - intrinsic }

        // Assignment is likely once the extrinsic given up by exercising early is below the dividend
        // captured. If the extrinsic can't be priced but it's ITM inside the window, force-close
        // anyway — the assignment loss dominates the slippage of an early close.
        return if (extrinsic == null || extrinsic < dividend) {
            StrategyExit(
                SpreadStatus.CLOSED_DIVIDEND_RISK,
                "dividend assignment risk: ex-div in ${daysToExDiv}d, short call ITM, " +
                    "extrinsic=${extrinsic ?: "n/a"} < dividend=$dividend",
            )
        } else {
            null
        }
    }
}
