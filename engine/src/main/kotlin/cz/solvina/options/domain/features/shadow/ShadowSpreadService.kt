package cz.solvina.options.domain.features.shadow

import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.scanner.StrategyParamsRegistry
import cz.solvina.options.domain.features.spread.underlyingStopBreached
import cz.solvina.options.domain.features.spread.underlyingStopTrigger
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("TRADES")

/**
 * Records spreads paper would not fill and follows them as if filled at mid (see [ShadowSpread]).
 *
 * Revalued once a day per market, from real option quotes, and closed by the same rules a real
 * spread follows: take-profit on the mid credit, the underlying stop, the time exit, settlement at
 * expiry. Once a day means an intraday-only touch is seen at the next check, not when it happened.
 */
@Service
class ShadowSpreadService(
    private val port: ShadowSpreadPort,
    private val marketDataPort: MarketDataPort,
    private val universePort: UniversePort,
    private val strategyParams: StrategyParamsRegistry,
    private val clock: Clock,
) {
    companion object {
        /** Outcomes where the engine wanted the trade and only the fill (or the screen width) stopped it. */
        val RECORDED_OUTCOMES =
            setOf(ExecutionOutcome.FLOOR_REACHED, ExecutionOutcome.TIMED_OUT, ExecutionOutcome.LIQUIDITY_REJECTED)
    }

    suspend fun record(
        request: TradeExecutionRequest,
        outcome: ExecutionOutcome,
    ) {
        if (outcome !in RECORDED_OUTCOMES) return
        val duplicate =
            port.findOpen().any {
                it.strategyId == request.strategyId &&
                    it.symbol == request.underlyingSymbol &&
                    it.expiry == request.soldContract.expiry &&
                    it.soldStrike.compareTo(request.soldContract.strike) == 0 &&
                    it.boughtStrike.compareTo(request.boughtContract.strike) == 0
            }
        if (duplicate) return
        val shadow =
            port.save(
                ShadowSpread(
                    strategyId = request.strategyId,
                    symbol = request.underlyingSymbol,
                    optionType = request.soldContract.type,
                    expiry = request.soldContract.expiry,
                    soldStrike = request.soldContract.strike,
                    boughtStrike = request.boughtContract.strike,
                    openedAt = Instant.now(clock),
                    sourceOutcome = outcome.name,
                    midCredit = request.targetCredit,
                    floorCredit = request.floorCredit,
                    naturalCredit = request.soldBid.subtract(request.boughtAsk),
                    underlyingAtEntry = request.underlyingPriceAtEntry,
                    ivRankAtEntry = request.ivRankAtEntry,
                ),
            )
        tradeLogger.info {
            "SHADOW ${shadow.symbol}  ${shadow.soldStrike}${shadow.optionType.ibkrCode}/${shadow.boughtStrike}" +
                "${shadow.optionType.ibkrCode}  exp=${shadow.expiry}  mid=\$${shadow.midCredit}  source=${shadow.sourceOutcome}"
        }
    }

    /** Revalues every open shadow whose market is open now. Returns how many were checked. */
    suspend fun revalueOpenMarkets(): Int {
        val due = port.findOpen().filter { runCatching { universePort.isMarketOpen(it.symbol) }.getOrDefault(false) }
        due.forEach { shadow ->
            runCatching { revalue(shadow) }
                .onFailure { e -> logger.warn { "[${shadow.symbol}] shadow revaluation failed: ${e.message}" } }
        }
        return due.size
    }

    internal suspend fun revalue(shadow: ShadowSpread): ShadowSpread {
        val today = LocalDate.now(clock)
        val now = Instant.now(clock)
        val spot = runCatching { marketDataPort.getUnderlyingPrice(shadow.symbol).amount }.getOrNull()
        val sold = OptionContract(shadow.symbol, shadow.expiry, shadow.soldStrike, shadow.optionType)
        val bought = OptionContract(shadow.symbol, shadow.expiry, shadow.boughtStrike, shadow.optionType)
        val soldMid = marketDataPort.getOptionMidLive(sold)?.amount
        val boughtMid = marketDataPort.getOptionMidLive(bought)?.amount
        val value =
            if (soldMid != null && boughtMid != null) {
                soldMid
                    .subtract(boughtMid)
                    .max(BigDecimal.ZERO)
                    .min(shadow.width)
                    .setScale(4, RoundingMode.HALF_UP)
            } else {
                null
            }
        val params = strategyParams.forStrategy(shadow.strategyId)
        val dte = ChronoUnit.DAYS.between(today, shadow.expiry).toInt()
        val tpValue = shadow.midCredit.multiply(BigDecimal.ONE.subtract(BigDecimal(params.takeProfitPercent)))
        val stopHit =
            params.underlyingStopEnabled &&
                spot != null &&
                underlyingStopBreached(
                    spot,
                    underlyingStopTrigger(shadow.soldStrike, shadow.optionType, params.underlyingStopBufferPct),
                    shadow.optionType,
                )

        val close: Pair<ShadowStatus, BigDecimal>? =
            when {
                dte <= 0 && spot != null -> ShadowStatus.EXPIRED to intrinsicValue(shadow, spot)
                value == null -> null
                stopHit -> ShadowStatus.CLOSED_STOP to value
                value <= tpValue -> ShadowStatus.CLOSED_PROFIT to value
                dte <= params.timeProfitDte -> ShadowStatus.CLOSED_TIME to value
                else -> null
            }

        val updated =
            if (close == null) {
                shadow.copy(lastValue = value ?: shadow.lastValue, lastUnderlying = spot ?: shadow.lastUnderlying, lastValuedAt = now)
            } else {
                val (status, exitValue) = close
                val reason =
                    when (status) {
                        ShadowStatus.CLOSED_STOP -> "underlying \$$spot through short strike ${shadow.soldStrike}"
                        ShadowStatus.CLOSED_PROFIT -> "value \$$exitValue ≤ TP \$${tpValue.setScale(4, RoundingMode.HALF_UP)}"
                        ShadowStatus.CLOSED_TIME -> "DTE $dte ≤ ${params.timeProfitDte}"
                        else -> "settled at expiry, underlying \$$spot"
                    }
                tradeLogger.info {
                    "SHADOW_CLOSE ${shadow.symbol}  ${shadow.soldStrike}/${shadow.boughtStrike}  $status  " +
                        "pnl=\$${shadow.midCredit.subtract(exitValue).multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)}  $reason"
                }
                shadow.copy(
                    status = status,
                    closeReason = reason,
                    closedAt = now,
                    exitValue = exitValue,
                    lastValue = exitValue,
                    lastUnderlying = spot ?: shadow.lastUnderlying,
                    lastValuedAt = now,
                )
            }
        return port.save(updated)
    }

    private fun intrinsicValue(
        shadow: ShadowSpread,
        spot: BigDecimal,
    ): BigDecimal {
        fun intrinsic(strike: BigDecimal) =
            when (shadow.optionType) {
                OptionType.PUT -> strike.subtract(spot)
                OptionType.CALL -> spot.subtract(strike)
            }.max(BigDecimal.ZERO)
        return intrinsic(shadow.soldStrike).subtract(intrinsic(shadow.boughtStrike)).setScale(4, RoundingMode.HALF_UP)
    }
}
