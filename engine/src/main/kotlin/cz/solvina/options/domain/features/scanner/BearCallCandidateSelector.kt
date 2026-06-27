package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("TRADES")

/**
 * Bear call entry selection — the mirror of [BullPutCandidateSelector] for CALLs:
 *   - SELL the short call at the delta target (the LOWER strike),
 *   - BUY the long call at `soldStrike + spreadWidth` (the HIGHER strike).
 *
 * Credit and max-risk math are identical to the bull put (`credit = soldMid − boughtMid`,
 * `maxRisk = width − credit`): the real quoted prices already reflect call skew, so there is no
 * artificial skew adjustment — any higher bar is the bear-call [BearCallScannerConfig.minCreditPerShare].
 * Ex-dividend entry avoidance is added in Phase 3 with the dividend-data pipeline.
 */
@Component
class BearCallCandidateSelector(
    private val volatilityPort: VolatilityPort,
    private val marketDataPort: MarketDataPort,
    private val optionChainPort: OptionChainPort,
    private val config: BearCallScannerConfig,
    private val clock: Clock,
) {
    suspend fun select(
        symbol: Symbol,
        totalCapital: Money,
    ): TradeExecutionRequest? {
        // 1. IV Rank filter
        val ivRank = volatilityPort.getIvRank(symbol)
        if (ivRank.rank < config.ivRankThreshold) {
            logger.info { "[$symbol] (bear-call) IV Rank ${"%.1f".format(ivRank.rank)}% < threshold ${config.ivRankThreshold}%, skipping" }
            tradeLogger.info { "SKIP   $symbol  (bear-call) iv_rank=${"%.1f".format(ivRank.rank)}% < threshold=${config.ivRankThreshold}%" }
            return null
        }

        val underlyingPrice = marketDataPort.getUnderlyingPrice(symbol)

        // 2. Select expiry
        val today = LocalDate.now(clock)
        val availableExpirations = optionChainPort.getAvailableExpirations(symbol)
        val expiry =
            availableExpirations
                .filter { exp ->
                    val dte = ChronoUnit.DAYS.between(today, exp).toInt()
                    dte in config.minDte..config.maxDte
                }.minByOrNull { exp ->
                    abs(ChronoUnit.DAYS.between(today, exp).toInt() - config.preferredDte)
                }
        if (expiry == null) {
            logger.info { "[$symbol] (bear-call) No valid expiry in [${config.minDte}, ${config.maxDte}] DTE, skipping" }
            return null
        }
        val dte = ChronoUnit.DAYS.between(today, expiry).toInt()
        logger.info { "[$symbol] (bear-call) Selected expiry $expiry ($dte DTE)" }

        // 3. Get option chain and find the best call to SELL (short leg, lower strike)
        val chain = optionChainPort.getOptionChain(symbol, expiry, underlyingPrice)
        val calls = chain.filter { it.contract.type == OptionType.CALL }

        val soldQuote =
            calls
                .filter { quote ->
                    val absDelta = abs(quote.greeks.delta)
                    absDelta in config.deltaMin..config.deltaMax
                }.minByOrNull { quote ->
                    abs(abs(quote.greeks.delta) - config.targetDelta)
                }
        if (soldQuote == null) {
            logger.info { "[$symbol] (bear-call) No call with delta in [${config.deltaMin}, ${config.deltaMax}] found, skipping" }
            return null
        }

        logger.info { "[$symbol] (bear-call) Selected sold call strike=${soldQuote.contract.strike} delta=${soldQuote.greeks.delta}" }

        // 4. Find bought strike — the protective long call sits ABOVE the short (soldStrike + width)
        val targetBoughtStrike = soldQuote.contract.strike.add(config.spreadWidthUsd)
        val boughtQuote =
            calls
                .filter { it.contract.strike >= targetBoughtStrike }
                .minByOrNull { it.contract.strike }
        if (boughtQuote == null) {
            logger.info { "[$symbol] (bear-call) No valid bought strike ≥ $targetBoughtStrike found, skipping" }
            return null
        }

        // 5. Credit check — use mid for filters, bid side for the initial order price
        val midCredit =
            soldQuote.mid.amount
                .subtract(boughtQuote.mid.amount)
                .setScale(4, RoundingMode.HALF_UP)
        if (midCredit < config.minCreditPerShare) {
            logger.info { "[$symbol] (bear-call) Credit $midCredit < minimum ${config.minCreditPerShare}, skipping" }
            return null
        }

        val actualSpreadWidth = boughtQuote.contract.strike.subtract(soldQuote.contract.strike)
        val maxRiskPerShare = actualSpreadWidth.subtract(midCredit).setScale(4, RoundingMode.HALF_UP)
        if (maxRiskPerShare <= BigDecimal.ZERO) {
            logger.info { "[$symbol] (bear-call) Credit \$$midCredit ≥ spread width \$$actualSpreadWidth, skipping" }
            return null
        }

        // 6. Money management check
        val maxRiskPerContract = maxRiskPerShare.multiply(BigDecimal("100"))
        val allowedRiskPerTrade = totalCapital.amount.multiply(BigDecimal(config.maxRiskPercent))
        if (maxRiskPerContract > allowedRiskPerTrade) {
            logger.info {
                "[$symbol] (bear-call) Max risk/contract $$maxRiskPerContract > allowed $${
                    "%.2f".format(allowedRiskPerTrade)
                }, skipping"
            }
            return null
        }

        // 7. Build execution request — start at mid, floor at bid-side natural cross price
        val bidCredit =
            soldQuote.bid.amount
                .subtract(boughtQuote.ask.amount)
                .setScale(4, RoundingMode.HALF_UP)

        // Require the ACHIEVABLE combo credit (natural cross = soldBid − boughtAsk) to clear the floor,
        // else the order could never fill at a credit we'd accept — skip rather than launch.
        if (bidCredit < config.minCreditPerShare) {
            logger.info {
                "[$symbol] (bear-call) Skipping — achievable combo bid \$$bidCredit < min credit \$${config.minCreditPerShare}"
            }
            tradeLogger.info { "SKIP   $symbol  (bear-call) combo_bid=\$$bidCredit < floor=\$${config.minCreditPerShare}" }
            return null
        }

        val floorCredit = bidCredit.max(config.minCreditPerShare).setScale(4, RoundingMode.HALF_UP)

        logger.info {
            "[$symbol] (bear-call) Launching execution: SELL ${soldQuote.contract.strike}C / BUY ${boughtQuote.contract.strike}C " +
                "target(mid)=\$$midCredit floor(bid)=\$$bidCredit maxRisk=\$$maxRiskPerShare"
        }
        tradeLogger.info {
            "CANDIDATE $symbol  (bear-call) ${soldQuote.contract.strike}C/${boughtQuote.contract.strike}C  exp=$expiry  dte=$dte" +
                "  iv_rank=${"%.1f".format(
                    ivRank.rank,
                )}%  underlying=${underlyingPrice.amount}  mid=\$$midCredit  max_risk=\$$maxRiskPerShare"
        }

        return TradeExecutionRequest(
            soldContract = soldQuote.contract,
            boughtContract = boughtQuote.contract,
            underlyingSymbol = symbol,
            strategyId = StrategyId.BEAR_CALL,
            targetCredit = midCredit,
            floorCredit = floorCredit,
            maxRiskPerShare = maxRiskPerShare,
            ivRankAtEntry = ivRank.rank,
            soldBid = soldQuote.bid.amount,
            soldAsk = soldQuote.ask.amount,
            boughtBid = boughtQuote.bid.amount,
            boughtAsk = boughtQuote.ask.amount,
            boughtMid = boughtQuote.mid.amount,
            underlyingPriceAtEntry = underlyingPrice.amount,
        )
    }
}
