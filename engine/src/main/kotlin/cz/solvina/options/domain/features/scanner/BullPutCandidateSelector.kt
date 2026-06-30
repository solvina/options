package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.features.universe.UniversePort
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

@Component
class BullPutCandidateSelector(
    private val volatilityPort: VolatilityPort,
    private val marketDataPort: MarketDataPort,
    private val optionChainPort: OptionChainPort,
    private val universePort: UniversePort,
    private val config: ScannerConfig,
    private val clock: Clock,
) {
    suspend fun select(
        symbol: Symbol,
        totalCapital: Money,
    ): TradeExecutionRequest? {
        val inst = universePort.get(symbol)

        // Resolve per-symbol overrides with global config fallback
        val ivRankThreshold = inst?.ivRankThreshold ?: config.ivRankThreshold
        val minDte = inst?.minDte ?: config.minDte
        val maxDte = inst?.maxDte ?: config.maxDte
        val preferredDte = inst?.preferredDte ?: config.preferredDte
        val targetDelta = inst?.targetDelta ?: config.targetDelta
        val deltaMin = inst?.deltaMin ?: config.deltaMin
        val deltaMax = inst?.deltaMax ?: config.deltaMax
        val spreadWidthUsd = inst?.spreadWidthUsd ?: config.spreadWidthUsd
        val minCreditPerShare = inst?.minCreditPerShare ?: config.minCreditPerShare
        val maxRiskPercent = inst?.maxRiskPercent ?: config.maxRiskPercent

        // 1. IV Rank filter
        val ivRank = volatilityPort.getIvRank(symbol)
        if (ivRank.rank < ivRankThreshold) {
            logger.info { "[$symbol] IV Rank ${"%.1f".format(ivRank.rank)}% < threshold $ivRankThreshold%, skipping" }
            tradeLogger.info { "SKIP   $symbol  iv_rank=${"%.1f".format(ivRank.rank)}% < threshold=$ivRankThreshold%" }
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
                    dte in minDte..maxDte
                }.minByOrNull { exp ->
                    abs(ChronoUnit.DAYS.between(today, exp).toInt() - preferredDte)
                }
        if (expiry == null) {
            logger.info { "[$symbol] No valid expiry in [$minDte, $maxDte] DTE, skipping" }
            return null
        }
        val dte = ChronoUnit.DAYS.between(today, expiry).toInt()
        logger.info { "[$symbol] Selected expiry $expiry ($dte DTE)" }

        // 3. Get option chain and find the best put
        val chain = optionChainPort.getOptionChain(symbol, expiry, underlyingPrice, StrategyId.BULL_PUT)
        val puts = chain.filter { it.contract.type == OptionType.PUT }

        val soldQuote =
            puts
                .filter { quote ->
                    val absDelta = abs(quote.greeks.delta)
                    absDelta in deltaMin..deltaMax
                }.minByOrNull { quote ->
                    abs(abs(quote.greeks.delta) - targetDelta)
                }
        if (soldQuote == null) {
            logger.info { "[$symbol] No put with delta in [$deltaMin, $deltaMax] found, skipping" }
            return null
        }

        logger.info { "[$symbol] Selected sold put strike=${soldQuote.contract.strike} delta=${soldQuote.greeks.delta}" }

        // 4. Find bought strike
        val targetBoughtStrike = soldQuote.contract.strike.subtract(spreadWidthUsd)
        val boughtQuote =
            puts
                .filter { it.contract.strike <= targetBoughtStrike }
                .maxByOrNull { it.contract.strike }
        if (boughtQuote == null) {
            logger.info { "[$symbol] No valid bought strike ≤ $targetBoughtStrike found, skipping" }
            return null
        }

        // 5. Credit check — use mid for filters, bid side for the initial order price
        val midCredit =
            soldQuote.mid.amount
                .subtract(boughtQuote.mid.amount)
                .setScale(4, RoundingMode.HALF_UP)
        if (midCredit < minCreditPerShare) {
            logger.info { "[$symbol] Credit $midCredit < minimum $minCreditPerShare, skipping" }
            return null
        }

        val actualSpreadWidth = soldQuote.contract.strike.subtract(boughtQuote.contract.strike)
        val maxRiskPerShare = actualSpreadWidth.subtract(midCredit).setScale(4, RoundingMode.HALF_UP)
        if (maxRiskPerShare <= BigDecimal.ZERO) {
            logger.info { "[$symbol] Credit \$$midCredit ≥ spread width \$$actualSpreadWidth (BS pricing artifact), skipping" }
            return null
        }

        // 6. Money management check
        val maxRiskPerContract = maxRiskPerShare.multiply(BigDecimal("100"))
        val allowedRiskPerTrade = totalCapital.amount.multiply(BigDecimal(maxRiskPercent))
        if (maxRiskPerContract > allowedRiskPerTrade) {
            logger.info {
                "[$symbol] Max risk/contract $$maxRiskPerContract > allowed $${
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

        // P1 — require the ACHIEVABLE combo credit (natural cross = soldBid − boughtAsk) to clear the
        // min-credit floor. Selecting off mid while the real combo bid sits below the floor was the
        // other no-fill cause: the order could never fill at a credit we'd accept. Skip, don't launch.
        if (bidCredit < minCreditPerShare) {
            logger.info {
                "[$symbol] Skipping — achievable combo bid \$$bidCredit < min credit \$$minCreditPerShare (would never fill at floor)"
            }
            tradeLogger.info { "SKIP   $symbol  combo_bid=\$$bidCredit < floor=\$$minCreditPerShare" }
            return null
        }

        val floorCredit = bidCredit.max(minCreditPerShare).setScale(4, RoundingMode.HALF_UP)

        logger.info {
            "[$symbol] Launching execution: SELL ${soldQuote.contract.strike}P / BUY ${boughtQuote.contract.strike}P " +
                "target(mid)=\$$midCredit floor(bid)=\$$bidCredit maxRisk=\$$maxRiskPerShare"
        }
        tradeLogger.info {
            "CANDIDATE $symbol  ${soldQuote.contract.strike}P/${boughtQuote.contract.strike}P  exp=$expiry  dte=$dte" +
                "  iv_rank=${"%.1f".format(
                    ivRank.rank,
                )}%  underlying=${underlyingPrice.amount}  mid=\$$midCredit  max_risk=\$$maxRiskPerShare"
        }

        return TradeExecutionRequest(
            soldContract = soldQuote.contract,
            boughtContract = boughtQuote.contract,
            underlyingSymbol = symbol,
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
