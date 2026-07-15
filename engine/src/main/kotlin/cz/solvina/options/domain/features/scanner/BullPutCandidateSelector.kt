package cz.solvina.options.domain.features.scanner

import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.notification.OpportunityNotificationPort
import cz.solvina.options.domain.features.notification.SpreadOpportunity
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
import java.time.Instant
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
    private val config: BullPutScannerConfig,
    private val clock: Clock,
    // Notification-only: emails the qualified candidate; NoOp default so unit tests need not supply it.
    private val opportunityNotifier: OpportunityNotificationPort = OpportunityNotificationPort.NoOp,
) {
    suspend fun select(
        symbol: Symbol,
        totalCapital: Money,
    ): CandidateResult {
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

        // Accumulates what we learn about this symbol; snapshotted into the reject/select result so the
        // scan-status table shows the full evaluation, not just entries.
        var detail = ScanDetail(strategyId = StrategyId.BULL_PUT, ivRankThreshold = ivRankThreshold.toDouble())

        fun reject(reason: RejectReason) = CandidateResult.Rejected(reason, detail)

        // 1. IV Rank filter
        val ivRank = volatilityPort.getIvRank(symbol)
        detail = detail.copy(ivRank = ivRank.rank)
        if (ivRank.rank < ivRankThreshold) {
            logger.info { "[$symbol] IV Rank ${"%.1f".format(ivRank.rank)}% < threshold $ivRankThreshold%, skipping" }
            tradeLogger.info { "SKIP   $symbol  iv_rank=${"%.1f".format(ivRank.rank)}% < threshold=$ivRankThreshold%" }
            return reject(RejectReason.IV_BELOW_THRESHOLD)
        }

        val underlyingPrice = marketDataPort.getUnderlyingPrice(symbol)
        detail = detail.copy(underlyingPrice = underlyingPrice.amount)

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
            return reject(RejectReason.NO_EXPIRY_IN_DTE)
        }
        val dte = ChronoUnit.DAYS.between(today, expiry).toInt()
        detail = detail.copy(expiry = expiry, dte = dte)
        logger.info { "[$symbol] Selected expiry $expiry ($dte DTE)" }

        // 3. Get option chain and find the best put
        val chain = optionChainPort.getOptionChain(symbol, expiry, underlyingPrice, StrategyId.BULL_PUT)
        val puts = chain.filter { it.contract.type == OptionType.PUT }
        if (puts.isEmpty()) {
            logger.info { "[$symbol] No live put quotes for $expiry, skipping" }
            return reject(RejectReason.NO_VALID_STRIKES)
        }

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
            return reject(RejectReason.NO_DELTA_IN_BAND)
        }
        detail = detail.copy(shortStrike = soldQuote.contract.strike, shortDelta = soldQuote.greeks.delta)

        logger.info { "[$symbol] Selected sold put strike=${soldQuote.contract.strike} delta=${soldQuote.greeks.delta}" }

        // 4. Find bought strike
        val targetBoughtStrike = soldQuote.contract.strike.subtract(spreadWidthUsd)
        val boughtQuote =
            puts
                .filter { it.contract.strike <= targetBoughtStrike }
                .maxByOrNull { it.contract.strike }
        if (boughtQuote == null) {
            logger.info { "[$symbol] No valid bought strike ≤ $targetBoughtStrike found, skipping" }
            return reject(RejectReason.NO_BOUGHT_LEG)
        }
        detail = detail.copy(longStrike = boughtQuote.contract.strike)

        // 5. Credit check — use mid for filters, bid side for the initial order price
        val midCredit =
            soldQuote.mid.amount
                .subtract(boughtQuote.mid.amount)
                .setScale(4, RoundingMode.HALF_UP)
        detail = detail.copy(midCredit = midCredit)
        if (midCredit < minCreditPerShare) {
            logger.info { "[$symbol] Credit $midCredit < minimum $minCreditPerShare, skipping" }
            return reject(RejectReason.CREDIT_BELOW_MIN)
        }

        val actualSpreadWidth = soldQuote.contract.strike.subtract(boughtQuote.contract.strike)
        val maxRiskPerShare = actualSpreadWidth.subtract(midCredit).setScale(4, RoundingMode.HALF_UP)
        detail = detail.copy(width = actualSpreadWidth, maxRiskPerShare = maxRiskPerShare)
        if (maxRiskPerShare <= BigDecimal.ZERO) {
            logger.info { "[$symbol] Credit \$$midCredit ≥ spread width \$$actualSpreadWidth (BS pricing artifact), skipping" }
            return reject(RejectReason.CREDIT_EXCEEDS_WIDTH)
        }

        // Crash-pricing guard: a true ~30-delta spread prices ~15–30% of width. A higher ratio means
        // the market prices near-even odds of finishing ITM — the greeks that passed the delta band
        // are vol-spike-distorted (NBIS sold at 49.5% of width, a BE candidate hit 90%, 2026-07-06/07).
        val creditPctOfWidth = midCredit.divide(actualSpreadWidth, 4, RoundingMode.HALF_UP).toDouble()
        detail = detail.copy(creditPctOfWidth = creditPctOfWidth)
        if (creditPctOfWidth > config.maxCreditPctOfWidth) {
            logger.info {
                "[$symbol] Credit \$$midCredit is ${"%.0f".format(creditPctOfWidth * 100)}% of width \$$actualSpreadWidth " +
                    "(max ${"%.0f".format(config.maxCreditPctOfWidth * 100)}%) — crash-priced spread, skipping"
            }
            tradeLogger.info {
                "SKIP   $symbol  credit/width=${"%.0f".format(creditPctOfWidth * 100)}% > ${"%.0f".format(
                    config.maxCreditPctOfWidth * 100,
                )}%  (crash-priced)"
            }
            return reject(RejectReason.CRASH_PRICED)
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
            return reject(RejectReason.RISK_EXCEEDS_BUDGET)
        }

        // 7. Build execution request — start at mid, floor at bid-side natural cross price
        val bidCredit =
            soldQuote.bid.amount
                .subtract(boughtQuote.ask.amount)
                .setScale(4, RoundingMode.HALF_UP)
        detail = detail.copy(bidCredit = bidCredit)

        // P1 — require the ACHIEVABLE combo credit (natural cross = soldBid − boughtAsk) to clear the
        // min-credit floor. Selecting off mid while the real combo bid sits below the floor was the
        // other no-fill cause: the order could never fill at a credit we'd accept. Skip, don't launch.
        if (bidCredit < minCreditPerShare) {
            logger.info {
                "[$symbol] Skipping — achievable combo bid \$$bidCredit < min credit \$$minCreditPerShare (would never fill at floor)"
            }
            tradeLogger.info { "SKIP   $symbol  combo_bid=\$$bidCredit < floor=\$$minCreditPerShare" }
            return reject(RejectReason.COMBO_BID_BELOW_FLOOR)
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

        // Notification-only: emails the full candidate while the per-leg quotes/greeks are still in
        // scope. Best-effort — the notifier swallows failures; the session lookup is guarded too.
        opportunityNotifier.notify(
            SpreadOpportunity(
                strategyId = StrategyId.BULL_PUT,
                symbol = symbol,
                session = runCatching { universePort.getMarketSchedule(symbol).session }.getOrDefault("US"),
                detectedAt = Instant.now(clock),
                ivRank = ivRank.rank,
                ivRankThreshold = ivRankThreshold,
                expiry = expiry,
                dte = dte,
                minDte = minDte,
                maxDte = maxDte,
                preferredDte = preferredDte,
                targetDelta = targetDelta,
                deltaMin = deltaMin,
                deltaMax = deltaMax,
                underlyingPrice = underlyingPrice.amount,
                shortLeg = soldQuote,
                longLeg = boughtQuote,
                midCredit = midCredit,
                bidCredit = bidCredit,
                minCreditPerShare = minCreditPerShare,
                targetWidth = spreadWidthUsd,
                actualWidth = actualSpreadWidth,
                maxRiskPerShare = maxRiskPerShare,
                totalCapital = totalCapital.amount,
                maxRiskPercent = maxRiskPercent,
                quantity = 1,
                takeProfitPercent = config.takeProfitPercent,
                stopLossPercent = config.stopLossPercent,
                timeProfitDte = config.timeProfitDte,
                driftProtectionPct = config.driftProtectionPct,
            ),
        )

        val request =
            TradeExecutionRequest(
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
        return CandidateResult.Selected(request, detail)
    }
}
