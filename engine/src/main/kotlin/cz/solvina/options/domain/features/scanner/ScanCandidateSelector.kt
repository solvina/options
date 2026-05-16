package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.market.OptionChainPort
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

@Component
class ScanCandidateSelector(
    private val volatilityPort: VolatilityPort,
    private val marketDataPort: MarketDataPort,
    private val optionChainPort: OptionChainPort,
    private val config: ScannerConfig,
    private val clock: Clock,
) {
    suspend fun select(symbol: Symbol, totalCapital: Money): TradeExecutionRequest? {
        // 1. IV Rank filter
        val ivRank = volatilityPort.getIvRank(symbol)
        if (ivRank.rank < config.ivRankThreshold) {
            logger.info { "[$symbol] IV Rank ${"%.1f".format(ivRank.rank)}% < threshold ${config.ivRankThreshold}%, skipping" }
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
            logger.info { "[$symbol] No valid expiry in [${config.minDte}, ${config.maxDte}] DTE, skipping" }
            return null
        }
        val dte = ChronoUnit.DAYS.between(today, expiry).toInt()
        logger.info { "[$symbol] Selected expiry $expiry ($dte DTE)" }

        // 3. Get option chain and find the best put
        val chain = optionChainPort.getOptionChain(symbol, expiry, underlyingPrice)
        val puts = chain.filter { it.contract.type == OptionType.PUT }

        val soldQuote =
            puts
                .filter { quote ->
                    val absDelta = abs(quote.greeks.delta)
                    absDelta in config.deltaMin..config.deltaMax
                }.minByOrNull { quote ->
                    abs(abs(quote.greeks.delta) - config.targetDelta)
                }
        if (soldQuote == null) {
            logger.info { "[$symbol] No put with delta in [${config.deltaMin}, ${config.deltaMax}] found, skipping" }
            return null
        }

        logger.info { "[$symbol] Selected sold put strike=${soldQuote.contract.strike} delta=${soldQuote.greeks.delta}" }

        // 4. Find bought strike
        val targetBoughtStrike = soldQuote.contract.strike.subtract(config.spreadWidthUsd)
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
        if (midCredit < config.minCreditPerShare) {
            logger.info { "[$symbol] Credit $midCredit < minimum ${config.minCreditPerShare}, skipping" }
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
        val allowedRiskPerTrade = totalCapital.amount.multiply(BigDecimal(config.maxRiskPercent))
        if (maxRiskPerContract > allowedRiskPerTrade) {
            logger.info {
                "[$symbol] Max risk/contract $$maxRiskPerContract > allowed $${
                    "%.2f".format(allowedRiskPerTrade)
                }, skipping"
            }
            return null
        }

        // 7. Build execution request
        val bidCredit =
            soldQuote.bid.amount
                .subtract(boughtQuote.ask.amount)
                .max(config.minCreditPerShare)
                .setScale(4, RoundingMode.HALF_UP)

        val floorCredit =
            bidCredit
                .multiply(BigDecimal.ONE.subtract(BigDecimal(config.floorCreditBuffer)))
                .setScale(4, RoundingMode.HALF_UP)

        logger.info {
            "[$symbol] Launching execution: SELL ${soldQuote.contract.strike}P / BUY ${boughtQuote.contract.strike}P " +
                "mid=\$$midCredit bid=\$$bidCredit floor=\$$floorCredit maxRisk=\$$maxRiskPerShare"
        }

        return TradeExecutionRequest(
            soldContract = soldQuote.contract,
            boughtContract = boughtQuote.contract,
            underlyingSymbol = symbol,
            targetCredit = bidCredit,
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
