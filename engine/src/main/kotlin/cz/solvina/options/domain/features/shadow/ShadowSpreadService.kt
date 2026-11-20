package cz.solvina.options.domain.features.shadow

import cz.solvina.options.domain.features.aireview.AiReviewService
import cz.solvina.options.domain.features.aireview.DecisionOutcome
import cz.solvina.options.domain.features.aireview.NEW_YORK
import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.scanner.ScannerPoolConfig
import cz.solvina.options.domain.features.scanner.StrategyParamsRegistry
import cz.solvina.options.domain.features.spread.underlyingStopBreached
import cz.solvina.options.domain.features.spread.underlyingStopTrigger
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

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
    private val poolConfig: ScannerPoolConfig = ScannerPoolConfig(),
    /** AI review D6: verdicts for the AI layer (`aiSelected`) and the per-task decision record. */
    private val aiReview: AiReviewService? = null,
) {
    companion object {
        /** Outcomes where the engine wanted the trade and only the fill (or the screen width) stopped it. */
        val PER_SHADOW_TIMEOUT = 60.seconds

        val RECORDED_OUTCOMES =
            setOf(ExecutionOutcome.FLOOR_REACHED, ExecutionOutcome.TIMED_OUT, ExecutionOutcome.LIQUIDITY_REJECTED)

        /**
         * A title recorded the moment it first qualified, before any AI verdict or pool decision, and
         * followed to its exit regardless of what happens next. Scores the AI's verdicts on the trade
         * itself: a veto is judged on how the vetoed spread would have done. Kept apart from the
         * decision rows: never "held", never selected, never in either simulated portfolio.
         */
        const val REVIEWED_CANDIDATE = "REVIEWED_CANDIDATE"
    }

    /** First qualification of [request]. One open candidate row per title. */
    suspend fun recordCandidate(
        request: TradeExecutionRequest,
        score: Double,
        firstSeenAt: Instant,
    ) {
        val duplicate =
            port.findOpen().any { it.symbol == request.underlyingSymbol && it.sourceOutcome == REVIEWED_CANDIDATE }
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
                    sourceOutcome = REVIEWED_CANDIDATE,
                    midCredit = request.targetCredit,
                    floorCredit = request.floorCredit,
                    naturalCredit = request.soldBid.subtract(request.boughtAsk),
                    underlyingAtEntry = request.underlyingPriceAtEntry,
                    ivRankAtEntry = request.ivRankAtEntry,
                    score = score,
                    firstSeenAt = firstSeenAt,
                ),
            )
        tradeLogger.info {
            "SHADOW ${shadow.symbol}  ${shadow.soldStrike}${shadow.optionType.ibkrCode}/${shadow.boughtStrike}" +
                "${shadow.optionType.ibkrCode}  exp=${shadow.expiry}  mid=\$${shadow.midCredit}  source=${shadow.sourceOutcome}"
        }
    }

    suspend fun record(
        request: TradeExecutionRequest,
        outcome: ExecutionOutcome,
    ) {
        if (outcome !in RECORDED_OUTCOMES) return
        // One position per title, whatever its strikes or direction: an account can hold only one
        // spread per symbol, and copies of one title are one bet (MU was counted 7 times).
        val duplicate =
            port.findOpen().any { it.symbol == request.underlyingSymbol && it.sourceOutcome != REVIEWED_CANDIDATE }
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

    /**
     * Records a pool decision, [ranked] best first. Every title not already held becomes a would-be
     * position with its rank and score. The best ones that fit the limits are marked selected,
     * forming the simulated small-account portfolio.
     *
     * AI layer (D6): a second pass over the same ranking with the same limits, skipping titles the
     * primary reviewer vetoed before this decision, marks `aiSelected`. It keeps its own counters
     * (open, new today, sectors taken from `aiSelected` rows), so a slot freed by a veto goes to the
     * next-ranked title and the two portfolios diverge over time. No verdict = approve (fail-open).
     */
    suspend fun recordDecision(ranked: List<ShadowCandidate>) {
        val now = Instant.now(clock)
        val startOfDay = LocalDate.now(clock).atStartOfDay(clock.zone).toInstant()
        val tradingDate = now.atZone(NEW_YORK).toLocalDate()
        // Candidate rows track verdicts on their own; they never count as a held title here.
        val open = port.findOpen().filter { it.sourceOutcome != REVIEWED_CANDIDATE }
        val all = port.findAll().filter { it.sourceOutcome != REVIEWED_CANDIDATE }
        val heldBySymbol = open.associateBy { it.symbol }
        val verdicts =
            runCatching { aiReview?.primaryVerdicts(tradingDate, now).orEmpty() }
                .onFailure { logger.warn { "AI verdicts unavailable for the decision: ${it.message}" } }
                .getOrDefault(emptyMap())
        val rules = Portfolio(open.filter { it.selected }, all.count { it.selected && !it.openedAt.isBefore(startOfDay) })
        val ai = Portfolio(open.filter { it.aiSelected }, all.count { it.aiSelected && !it.openedAt.isBefore(startOfDay) })
        val outcomes = mutableListOf<DecisionOutcome>()
        ranked.forEachIndexed { index, candidate ->
            val request = candidate.request
            val held = heldBySymbol[request.underlyingSymbol]
            if (held != null) {
                outcomes += outcome(request, index, candidate.score, "ALREADY_HELD", held.id)
                return@forEachIndexed
            }
            val maxLoss = request.maxRiskPerShare.toDouble() * 100
            val sector = universePort.sectorOf(request.underlyingSymbol)
            val eligible = candidate.flags.isEmpty() && maxLoss <= poolConfig.portfolioMaxLossUsd
            val verdict = verdicts[request.underlyingSymbol]?.takeIf { it.strategyId == request.strategyId }
            val selected = eligible && rules.take(sector)
            val aiSelected = eligible && verdict?.vetoes != true && ai.take(sector)
            val saved =
                port.save(
                    ShadowSpread(
                        strategyId = request.strategyId,
                        symbol = request.underlyingSymbol,
                        optionType = request.soldContract.type,
                        expiry = request.soldContract.expiry,
                        soldStrike = request.soldContract.strike,
                        boughtStrike = request.boughtContract.strike,
                        openedAt = now,
                        sourceOutcome = "POOL_DECISION",
                        midCredit = request.targetCredit,
                        floorCredit = request.floorCredit,
                        naturalCredit = request.soldBid.subtract(request.boughtAsk),
                        underlyingAtEntry = request.underlyingPriceAtEntry,
                        ivRankAtEntry = request.ivRankAtEntry,
                        score = candidate.score,
                        decisionRank = index + 1,
                        firstSeenAt = candidate.firstSeenAt,
                        selected = selected,
                        flags = candidate.flags.map { it.name }.toSet(),
                        aiTaskId = verdict?.taskId,
                        aiVerdict = verdict?.verdict?.name,
                        aiConfidence = verdict?.confidence,
                        aiSelected = aiSelected,
                    ),
                )
            outcomes += outcome(request, index, candidate.score, "RECORDED", saved.id)
        }
        runCatching { aiReview?.recordDecision(tradingDate, now, outcomes) }
            .onFailure { logger.warn { "AI review decision record failed: ${it.message}" } }
    }

    /** Running limits of one simulated portfolio during a decision. */
    private inner class Portfolio(
        openRows: List<ShadowSpread>,
        private var newToday: Int,
    ) {
        private var open = openRows.size
        private val sectors = openRows.mapNotNull { universePort.sectorOf(it.symbol) }.toMutableSet()

        /** True (and counted) when a title in [sector] still fits the limits. */
        fun take(sector: String?): Boolean {
            val fits =
                open < poolConfig.portfolioMaxOpen &&
                    newToday < poolConfig.portfolioMaxNewPerDay &&
                    !(poolConfig.portfolioOnePerSector && sector != null && sector in sectors)
            if (fits) {
                open++
                newToday++
                sector?.let { sectors += it }
            }
            return fits
        }
    }

    private fun outcome(
        request: TradeExecutionRequest,
        index: Int,
        score: Double,
        action: String,
        shadowId: UUID?,
    ) = DecisionOutcome(
        strategyId = request.strategyId,
        symbol = request.underlyingSymbol,
        action = action,
        rank = index + 1,
        score = score,
        expiry = request.soldContract.expiry,
        shortStrike = request.soldContract.strike,
        longStrike = request.boughtContract.strike,
        midCredit = request.targetCredit,
        shadowSpreadId = shadowId,
    )

    /** Revalues every open shadow whose market is open now. Returns how many were checked. */
    suspend fun revalueOpenMarkets(): Int {
        val due = port.findOpen().filter { runCatching { universePort.isMarketOpen(it.symbol) }.getOrDefault(false) }
        // Each position gets its own time budget: on 2026-11-02 one ORCL price request never answered,
        // held the whole pass for its 15-minute ceiling, and no position was revalued that day.
        due.forEach { shadow ->
            val done = withTimeoutOrNull(PER_SHADOW_TIMEOUT) { runCatching { revalue(shadow) } }
            when {
                done == null -> logger.warn { "[${shadow.symbol}] shadow revaluation timed out after $PER_SHADOW_TIMEOUT — next pass" }
                done.isFailure -> logger.warn { "[${shadow.symbol}] shadow revaluation failed: ${done.exceptionOrNull()?.message}" }
            }
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
        val saved = port.save(updated)
        runCatching { port.saveMark(saved.id, now, value, spot, saved.status) }
            .onFailure { logger.warn { "[${shadow.symbol}] shadow mark not saved: ${it.message}" } }
        return saved
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
