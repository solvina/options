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
    private val universePort: UniversePort,
    private val config: BearCallScannerConfig,
    private val clock: Clock,
    // Notification-only: emails the qualified candidate; NoOp default so unit tests need not supply it.
    private val opportunityNotifier: OpportunityNotificationPort = OpportunityNotificationPort.NoOp,
) {
    suspend fun select(
        symbol: Symbol,
        totalCapital: Money,
    ): CandidateResult {
        // Accumulates what we learn about this symbol; snapshotted into the reject/select result so the
        // scan-status table shows the full evaluation, not just entries.
        var detail = ScanDetail(strategyId = StrategyId.BEAR_CALL, ivRankThreshold = config.ivRankThreshold.toDouble())

        fun reject(reason: RejectReason) = CandidateResult.Rejected(reason, detail)

        // 0. Ex-dividend entry buffer — don't open a short call right before an ex-dividend date
        // (US/American-style early-assignment risk). Inert until the dividend-data pipeline runs.
        val exDiv = universePort.get(symbol)?.exDividendDate
        if (exDiv != null && universePort.getMarketSchedule(symbol).session == "US") {
            val daysToExDiv = ChronoUnit.DAYS.between(LocalDate.now(clock), exDiv)
            val bufferDays = config.exDividendEntryBufferHours / 24
            if (daysToExDiv in 0..bufferDays) {
                logger.info { "[$symbol] (bear-call) Skipping entry — ex-dividend in ${daysToExDiv}d (within ${bufferDays}d buffer)" }
                return reject(RejectReason.EX_DIVIDEND_BUFFER)
            }
        }

        // 1. IV Rank filter
        val ivRank = volatilityPort.getIvRank(symbol)
        detail = detail.copy(ivRank = ivRank.rank)
        if (ivRank.rank < config.ivRankThreshold) {
            logger.info { "[$symbol] (bear-call) IV Rank ${"%.1f".format(ivRank.rank)}% < threshold ${config.ivRankThreshold}%, skipping" }
            tradeLogger.info { "SKIP   $symbol  (bear-call) iv_rank=${"%.1f".format(ivRank.rank)}% < threshold=${config.ivRankThreshold}%" }
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
                    dte in config.minDte..config.maxDte
                }.minByOrNull { exp ->
                    abs(ChronoUnit.DAYS.between(today, exp).toInt() - config.preferredDte)
                }
        if (expiry == null) {
            logger.info { "[$symbol] (bear-call) No valid expiry in [${config.minDte}, ${config.maxDte}] DTE, skipping" }
            return reject(RejectReason.NO_EXPIRY_IN_DTE)
        }
        val dte = ChronoUnit.DAYS.between(today, expiry).toInt()
        detail = detail.copy(expiry = expiry, dte = dte)
        logger.info { "[$symbol] (bear-call) Selected expiry $expiry ($dte DTE)" }

        // 2b. Earnings gate — mirror of the bull-put rule: never open a position whose life spans
        // a scheduled earnings report (see BullPutCandidateSelector for rationale).
        val earnings = universePort.get(symbol)?.nextEarningsDate
        if (earnings != null && !earnings.isBefore(today) && !earnings.isAfter(expiry)) {
            logger.info { "[$symbol] (bear-call) Skipping entry — earnings $earnings inside position window (expiry $expiry)" }
            tradeLogger.info { "SKIP   $symbol  (bear-call) earnings=$earnings before expiry=$expiry" }
            return reject(RejectReason.EARNINGS_BEFORE_EXPIRY)
        }

        // 3. Get option chain and find the best call to SELL (short leg, lower strike)
        val chain = optionChainPort.getOptionChain(symbol, expiry, underlyingPrice, StrategyId.BEAR_CALL)
        val calls = chain.filter { it.contract.type == OptionType.CALL }
        if (calls.isEmpty()) {
            logger.info { "[$symbol] (bear-call) No live call quotes for $expiry, skipping" }
            return reject(RejectReason.NO_VALID_STRIKES)
        }

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
            return reject(RejectReason.NO_DELTA_IN_BAND)
        }
        detail = detail.copy(shortStrike = soldQuote.contract.strike, shortDelta = soldQuote.greeks.delta)

        logger.info { "[$symbol] (bear-call) Selected sold call strike=${soldQuote.contract.strike} delta=${soldQuote.greeks.delta}" }

        // 4. Find bought strike — the protective long call sits ABOVE the short (soldStrike + width)
        val targetBoughtStrike = soldQuote.contract.strike.add(config.spreadWidthUsd)
        val boughtQuote =
            calls
                .filter { it.contract.strike >= targetBoughtStrike }
                .minByOrNull { it.contract.strike }
        if (boughtQuote == null) {
            logger.info { "[$symbol] (bear-call) No valid bought strike ≥ $targetBoughtStrike found, skipping" }
            return reject(RejectReason.NO_BOUGHT_LEG)
        }
        detail = detail.copy(longStrike = boughtQuote.contract.strike)

        // 5. Credit check — use mid for filters, bid side for the initial order price
        val midCredit =
            soldQuote.mid.amount
                .subtract(boughtQuote.mid.amount)
                .setScale(4, RoundingMode.HALF_UP)
        detail = detail.copy(midCredit = midCredit)
        if (midCredit < config.minCreditPerShare) {
            logger.info { "[$symbol] (bear-call) Credit $midCredit < minimum ${config.minCreditPerShare}, skipping" }
            return reject(RejectReason.CREDIT_BELOW_MIN)
        }

        val actualSpreadWidth = boughtQuote.contract.strike.subtract(soldQuote.contract.strike)
        val maxRiskPerShare = actualSpreadWidth.subtract(midCredit).setScale(4, RoundingMode.HALF_UP)
        detail = detail.copy(width = actualSpreadWidth, maxRiskPerShare = maxRiskPerShare)
        if (maxRiskPerShare <= BigDecimal.ZERO) {
            logger.info { "[$symbol] (bear-call) Credit \$$midCredit ≥ spread width \$$actualSpreadWidth, skipping" }
            return reject(RejectReason.CREDIT_EXCEEDS_WIDTH)
        }

        // Crash-pricing guard — see BullPutCandidateSelector: a mid above this fraction of width
        // means the market prices near-even ITM odds and the delta band was vol-spike-distorted.
        val creditPctOfWidth = midCredit.divide(actualSpreadWidth, 4, RoundingMode.HALF_UP).toDouble()
        detail = detail.copy(creditPctOfWidth = creditPctOfWidth)
        if (creditPctOfWidth > config.maxCreditPctOfWidth) {
            logger.info {
                "[$symbol] (bear-call) Credit \$$midCredit is ${"%.0f".format(creditPctOfWidth * 100)}% of width " +
                    "\$$actualSpreadWidth (max ${"%.0f".format(config.maxCreditPctOfWidth * 100)}%) — crash-priced, skipping"
            }
            tradeLogger.info {
                "SKIP   $symbol  (bear-call) credit/width=${"%.0f".format(creditPctOfWidth * 100)}% > ${"%.0f".format(
                    config.maxCreditPctOfWidth * 100,
                )}%  (crash-priced)"
            }
            return reject(RejectReason.CRASH_PRICED)
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
            return reject(RejectReason.RISK_EXCEEDS_BUDGET)
        }

        // 7. Build execution request — start at mid, floor at bid-side natural cross price
        val bidCredit =
            soldQuote.bid.amount
                .subtract(boughtQuote.ask.amount)
                .setScale(4, RoundingMode.HALF_UP)
        detail = detail.copy(bidCredit = bidCredit)

        // Require the ACHIEVABLE combo credit (natural cross = soldBid − boughtAsk) to clear the floor,
        // else the order could never fill at a credit we'd accept — skip rather than launch.
        if (bidCredit < config.minCreditPerShare) {
            logger.info {
                "[$symbol] (bear-call) Skipping — achievable combo bid \$$bidCredit < min credit \$${config.minCreditPerShare}"
            }
            tradeLogger.info { "SKIP   $symbol  (bear-call) combo_bid=\$$bidCredit < floor=\$${config.minCreditPerShare}" }
            return reject(RejectReason.COMBO_BID_BELOW_FLOOR)
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

        // Notification-only: emails the full candidate while the per-leg quotes/greeks are still in
        // scope. Best-effort — the notifier swallows failures; the session lookup is guarded too.
        opportunityNotifier.notify(
            SpreadOpportunity(
                strategyId = StrategyId.BEAR_CALL,
                symbol = symbol,
                session = runCatching { universePort.getMarketSchedule(symbol).session }.getOrDefault("US"),
                detectedAt = Instant.now(clock),
                ivRank = ivRank.rank,
                ivRankThreshold = config.ivRankThreshold,
                expiry = expiry,
                dte = dte,
                minDte = config.minDte,
                maxDte = config.maxDte,
                preferredDte = config.preferredDte,
                targetDelta = config.targetDelta,
                deltaMin = config.deltaMin,
                deltaMax = config.deltaMax,
                underlyingPrice = underlyingPrice.amount,
                shortLeg = soldQuote,
                longLeg = boughtQuote,
                midCredit = midCredit,
                bidCredit = bidCredit,
                minCreditPerShare = config.minCreditPerShare,
                targetWidth = config.spreadWidthUsd,
                actualWidth = actualSpreadWidth,
                maxRiskPerShare = maxRiskPerShare,
                totalCapital = totalCapital.amount,
                maxRiskPercent = config.maxRiskPercent,
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
        return CandidateResult.Selected(request, detail)
    }
}
