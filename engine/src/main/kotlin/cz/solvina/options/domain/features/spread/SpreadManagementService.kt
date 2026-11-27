package cz.solvina.options.domain.features.spread

import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.execution.TradeExecutionPort
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.market.MarketTickPort
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.LegOrder
import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.order.OrderPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.order.ceilToOptionTick
import cz.solvina.options.domain.features.order.floorToOptionTick
import cz.solvina.options.domain.features.order.optionTickFor
import cz.solvina.options.domain.features.order.roundToOptionTick
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.scanner.StrategyParamsRegistry
import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.spread.service.QuoteHealthService
import cz.solvina.options.domain.features.spread.strategy.SpreadStrategyRegistry
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("TRADES")

private const val SOLD_LEG = "SOLD"
private const val BOUGHT_LEG = "BOUGHT"

/** "450P/445P" (bull put) or "520C/525C" (bear call) — for TRADES-journal lines. */
private val Spread.legsLabel: String
    get() =
        "${soldLeg.contract.strike}${soldLeg.contract.type.ibkrCode}/" +
            "${boughtLeg.contract.strike}${boughtLeg.contract.type.ibkrCode}"

@Service
class SpreadManagementService(
    private val closers: SpreadCloserRegistry,
    private val marketDataPort: MarketDataPort,
    private val orderPort: OrderPort,
    private val universePort: UniversePort,
    private val volatilityPort: VolatilityPort,
    private val executionPort: TradeExecutionPort,
    private val config: ScannerConfig,
    private val clock: Clock,
    private val quoteHealthService: QuoteHealthService,
    private val strategyRegistry: SpreadStrategyRegistry,
    private val strategyParams: StrategyParamsRegistry,
    private val positionsPort: cz.solvina.options.domain.features.account.PositionsPort? = null,
    // Optional like positionsPort: injected in prod, absent in unit tests. Without it, stop-loss
    // closes fall back to plain market orders (the pre-2026-07 behaviour).
    private val marketTickPort: MarketTickPort? = null,
    // Combo exits (see ScannerConfig.exitComboEnabled). Absent in most unit tests → legacy exit path.
    private val orderExecutionPort: OrderExecutionPort? = null,
    // Combo closes run in this scope so a 5-min close never holds up the other spreads' stop checks.
    // Null (unit tests) → the close runs inline.
    private val closeScope: CoroutineScope? = null,
) {
    // Spreads with a combo close in flight — skipped by the monitor until the attempt resolves.
    private val activeComboCloses = ConcurrentHashMap<UUID, Job>()

    // Per spread: legs whose force-close order the broker reported FILLED (see forceCloseSpread).
    private val closeFilledLegs = ConcurrentHashMap<UUID, Set<String>>()

    // Consecutive SL-breach observations per spread (in-memory: a restart resets the count, which
    // only delays a stop by one extra monitor cycle). One garbage mid must never market-out a
    // position — LITE was stopped 29 s after entry on a single wide-book observation.
    private val slBreachCounts = ConcurrentHashMap<UUID, Int>()

    sealed interface ManualCloseResult {
        data class Closed(
            val spread: Spread,
        ) : ManualCloseResult

        data object NotFound : ManualCloseResult

        data class AlreadyClosed(
            val spread: Spread,
        ) : ManualCloseResult
    }

    suspend fun softClose(id: UUID): ManualCloseResult = manualClose(id, useMarket = false)

    suspend fun forceClose(id: UUID): ManualCloseResult = manualClose(id, useMarket = true)

    private val forceable = setOf(SpreadStatus.OPEN, SpreadStatus.CLOSING, SpreadStatus.CLOSED_STOP)

    private suspend fun manualClose(
        id: UUID,
        useMarket: Boolean,
    ): ManualCloseResult {
        val spread = closers.findById(id) ?: return ManualCloseResult.NotFound
        val allowedStatuses = if (useMarket) forceable else setOf(SpreadStatus.OPEN)
        if (spread.status !in allowedStatuses) return ManualCloseResult.AlreadyClosed(spread)

        val soldMid = marketDataPort.getOptionMid(spread.soldLeg.contract)
        val boughtMid = marketDataPort.getOptionMid(spread.boughtLeg.contract)
        val spreadValue = soldMid.amount.subtract(boughtMid.amount)
        val exitContext = captureExitContext(spread)

        // Soft close trades both legs unconditionally — never on a spread already flat at the broker
        // (it would open the reverse spread), and with one leg flat close only the held one.
        if (!useMarket) {
            val held = legsStillHeld(spread)
            if (held == Pair(false, false)) {
                logger.warn { "[${spread.symbol}] Soft close refused: both legs are flat at the broker — no order placed" }
                return ManualCloseResult.AlreadyClosed(spread)
            }
            if (held != null && held != Pair(true, true)) {
                logger.warn { "[${spread.symbol}] Soft close with only one leg held ($held) — closing the held leg only" }
                return ManualCloseResult.Closed(forceCloseSpread(spread, spreadValue, exitContext = exitContext))
            }
            // Same combo ladder as a take-profit (2026-11-27). Runs in the background in prod: the
            // response returns the spread still OPEN and it flips to CLOSED_MANUAL when the combo fills;
            // an unfilled attempt leaves it OPEN (press again, or force close).
            if (comboCloseEligible(spread, Int.MAX_VALUE)) {
                startComboClose(spread, SpreadStatus.CLOSED_MANUAL, "MANUAL_SOFT", exitContext)
                return ManualCloseResult.Closed(closers.findById(id) ?: spread)
            }
        }

        val closed =
            if (useMarket) {
                forceCloseSpread(spread, spreadValue, exitContext = exitContext)
            } else {
                closeSpread(spread, SpreadStatus.CLOSED_MANUAL, soldMid, boughtMid, spreadValue, exitContext)
                // Re-fetch so we return the actual persisted state (closeSpread updates the DB)
                closers.findById(id) ?: spread
            }
        return ManualCloseResult.Closed(closed)
    }

    private suspend fun forceCloseSpread(
        spread: Spread,
        estimatedSpreadValue: BigDecimal,
        closeStatus: SpreadStatus = SpreadStatus.CLOSED_MANUAL,
        closeReason: String = "MANUAL_FORCE",
        exitContext: Pair<BigDecimal?, BigDecimal?> = Pair(null, null),
    ): Spread {
        logger.info { "[${spread.symbol}] Force-closing at market (reason=$closeReason)" }

        val spreadKey = spread.id
        // Legs whose close order the BROKER reported FILLED in an earlier attempt. The position feed
        // can lag a fill; a retry that trusted only the feed would fire a second market order on an
        // already-closed leg and open the opposite position (2026-11-27 review).
        val filledEarlier = spreadKey?.let { closeFilledLegs[it] }.orEmpty()

        // C5: only close legs that are still held in IBKR. On a retry after a partial close, the
        // leg that already filled shows flat here and is skipped - so we never fire a second
        // order on it (which would open an unintended opposite position). null = positions can't
        // be queried -> close both, preserving the prior best-effort behaviour.
        val held = legsStillHeld(spread)
        val closeSold = (held?.first ?: true) && SOLD_LEG !in filledEarlier
        val closeBought = (held?.second ?: true) && BOUGHT_LEG !in filledEarlier

        var soldOrder: LegOrder? = null
        var boughtOrder: LegOrder? = null
        if (closeSold) {
            soldOrder =
                runCatching { orderPort.placeMarketOrder(spread.soldLeg.contract, LegAction.BUY, spread.quantity) }
                    .onFailure { e ->
                        logger.warn { "[${spread.symbol}] Sold-leg market order timed out (order still submitted): ${e.message}" }
                    }.getOrNull()
        } else {
            logger.info { "[${spread.symbol}] Sold leg already flat/filled - skipping buy-back (idempotent close)" }
        }
        if (closeBought) {
            boughtOrder =
                runCatching { orderPort.placeMarketOrder(spread.boughtLeg.contract, LegAction.SELL, spread.quantity) }
                    .onFailure { e ->
                        logger.warn { "[${spread.symbol}] Bought-leg market order timed out (order still submitted): ${e.message}" }
                    }.getOrNull()
        } else {
            logger.info { "[${spread.symbol}] Bought leg already flat/filled - skipping sell-back (idempotent close)" }
        }

        val soldFilled = soldOrder?.status == OrderStatus.FILLED
        val boughtFilled = boughtOrder?.status == OrderStatus.FILLED
        if (spreadKey != null && (soldFilled || boughtFilled)) {
            val nowFilled =
                buildSet {
                    if (soldFilled) add(SOLD_LEG)
                    if (boughtFilled) add(BOUGHT_LEG)
                }
            closeFilledLegs.merge(spreadKey, nowFilled) { a, b -> a + b }
        }
        // Each leg is done when the broker confirmed this attempt's (or an earlier attempt's) order
        // FILLED, or when it was already flat and we sent nothing for it.
        val soldDone = soldFilled || (!closeSold && (SOLD_LEG in filledEarlier || held?.first == false))
        val boughtDone = boughtFilled || (!closeBought && (BOUGHT_LEG in filledEarlier || held?.second == false))

        // Order-level FILLED is authoritative. The position feed only judges legs without a fill
        // confirmation, and a failed check cancels only orders that did NOT fill - a retry must never
        // re-close a leg that filled.
        val verified = (soldDone && boughtDone) || verifyPositionClosed(spread)
        if (!verified) {
            logger.warn {
                "[${spread.symbol}] Position verification FAILED - legs still open in IBKR. " +
                    "Cancelling unfilled orders from this attempt to prevent duplicates."
            }
            for ((label, order) in listOf("sold" to soldOrder, "bought" to boughtOrder)) {
                if (order == null || order.orderId <= 0 || order.status == OrderStatus.FILLED) continue
                runCatching { orderPort.cancelOrder(order.orderId) }
                    .onSuccess {
                        logger.info { "[${spread.symbol}] Cancelled $label-leg order ${order.orderId} due to verification timeout" }
                    }.onFailure { e ->
                        logger.warn { "[${spread.symbol}] Failed to cancel $label-leg order ${order.orderId}: ${e.message}" }
                    }
            }
            // Keep spread in CLOSING state; next retryClose will try again
            return spread
        }
        spreadKey?.let { closeFilledLegs.remove(it) }

        val closePrice = realizedCloseValue(spread, soldOrder?.orderId, boughtOrder?.orderId) ?: estimatedSpreadValue
        val updated =
            closers.forSpread(spread).close(
                spread = spread,
                status = closeStatus,
                closeReason = closeReason,
                closePrice = closePrice,
                underlyingAtExit = exitContext.first,
                ivAtExit = exitContext.second,
            )
        logger.info {
            "[${spread.symbol}] Force-closed. status=$closeStatus value=\$$closePrice (pre-order estimate \$$estimatedSpreadValue)"
        }
        return updated
    }

    /** True when [pos] is a non-zero option position matching [contract] on this [symbol]. */
    private fun positionMatchesLeg(
        pos: AccountPosition,
        symbol: cz.solvina.options.domain.models.Symbol,
        contract: OptionContract,
    ): Boolean =
        pos.symbol == symbol.value &&
            pos.secType == "OPT" &&
            pos.expiry == contract.expiry &&
            // BigDecimal `==` is scale-sensitive (445 != 445.0) — compareTo is the correct comparison
            // for a strike that may arrive from IBKR with a different scale than our own contract.
            pos.strike?.compareTo(contract.strike) == 0 &&
            // IBKR reports the right via Types.Right.getApiString() = "P"/"C" (first char only),
            // never "Put"/"Call". Derive the expected code from the leg's own contract so this
            // matches both puts (bull put) and calls (bear call). Matching against "Put" here was a
            // latent bug: it never matched, so force-close skipped buy-backs and verification
            // falsely passed, marking spreads CLOSED while the real IBKR legs stayed open (orphans).
            pos.optionRight?.equals(contract.type.ibkrCode, ignoreCase = true) == true &&
            pos.quantity.compareTo(BigDecimal.ZERO) != 0

    /**
     * Snapshot which legs are still held in IBKR, as (soldHeld, boughtHeld). Returns null when
     * positions can't be queried, in which case the caller closes both legs (best-effort).
     */
    private suspend fun legsStillHeld(spread: Spread): Pair<Boolean, Boolean>? {
        val port = positionsPort ?: return null
        val positions = runCatching { port.getPositions() }.getOrNull() ?: return null
        val soldHeld = positions.any { positionMatchesLeg(it, spread.symbol, spread.soldLeg.contract) }
        val boughtHeld = positions.any { positionMatchesLeg(it, spread.symbol, spread.boughtLeg.contract) }
        return soldHeld to boughtHeld
    }

    /**
     * Verify that both legs of a spread are actually closed in IBKR.
     * Polls position feed up to 10 times with 500ms delays (5s total timeout).
     * Returns true if both legs are verified closed, false otherwise.
     */
    private suspend fun verifyPositionClosed(spread: Spread): Boolean {
        val port = positionsPort
        if (port == null) {
            logger.debug { "[${spread.symbol}] Position verification skipped (PositionsPort not available)" }
            return true // Assume closed if we can't verify
        }

        // Require TWO consecutive "both legs flat" observations before trusting a close. A single
        // empty/partial snapshot is not enough: reqPositions can return positionEnd before the position
        // rows populate, and on this box the positions feed degrades (IBKR 10197 "no market data during
        // competing live session"). A transient empty snapshot read as "flat" is exactly how a live leg
        // gets marked CLOSED and becomes an orphan. A genuinely flat account simply confirms twice.
        var consecutiveFlat = 0
        repeat(10) { attempt ->
            runCatching {
                val positions = port.getPositions()
                val soldLegClosed = !positions.any { positionMatchesLeg(it, spread.symbol, spread.soldLeg.contract) }
                val boughtLegClosed = !positions.any { positionMatchesLeg(it, spread.symbol, spread.boughtLeg.contract) }

                if (soldLegClosed && boughtLegClosed) {
                    consecutiveFlat++
                    if (consecutiveFlat >= 2) {
                        logger.info { "[${spread.symbol}] Position verification SUCCESS (attempt $attempt, confirmed twice)" }
                        return true
                    }
                    logger.debug { "[${spread.symbol}] Legs appear flat (attempt $attempt) — confirming once more" }
                    delay(500)
                } else {
                    consecutiveFlat = 0
                    if (attempt < 9) {
                        logger.debug { "[${spread.symbol}] Position not yet closed (attempt $attempt), retrying..." }
                        delay(500)
                    }
                }
            }.onFailure { e ->
                // A failed/timed-out query is NOT evidence of closure — reset so a degraded feed can
                // never accumulate toward a false "flat" verdict.
                consecutiveFlat = 0
                logger.warn(e) { "[${spread.symbol}] Position verification error on attempt $attempt" }
            }
        }

        logger.warn { "[${spread.symbol}] Position verification TIMEOUT after 10 attempts (5s)" }
        return false
    }

    suspend fun checkExits() {
        val openSpreads = closers.allOpen()
        val closingSpreads = closers.allClosing()

        if (openSpreads.isEmpty() && closingSpreads.isEmpty()) {
            logger.debug { "No open spreads to monitor" }
            return
        }

        val tradableOpen = openSpreads.filter { universePort.isMarketOpen(it.symbol) }
        val tradableClosing = closingSpreads.filter { universePort.isMarketOpen(it.symbol) }
        val skipped = (openSpreads.size - tradableOpen.size) + (closingSpreads.size - tradableClosing.size)
        if (skipped > 0) {
            logger.debug { "Skipped $skipped spread(s): exchange not in regular trading hours" }
        }

        // Hold a persistent quote stream per tradable leg instead of snapshotting each cycle: opens
        // streams for newly-tracked positions and cancels them for closed ones (2026-07-21). Best
        // effort — checkSpreadExit falls back to a one-off snapshot when a stream isn't ready.
        runCatching {
            marketDataPort.reconcilePositionQuoteStreams(
                (tradableOpen + tradableClosing).flatMap { listOf(it.soldLeg.contract, it.boughtLeg.contract) },
            )
        }.onFailure { e -> logger.warn { "position quote stream reconcile failed: ${e.message}" } }

        // Spreads are evaluated CONCURRENTLY: a TP/time exit now chases a limit order that can block
        // for minutes, and running spreads sequentially would let one slow chase delay a stop-loss
        // check on every other symbol. Each spread is independent (per-symbol exposure rules forbid
        // two spreads on one symbol), so per-spread ordering is preserved while symbols don't wait
        // on each other. The scheduler-level mutex still prevents overlapping checkExits runs.
        if (tradableOpen.isNotEmpty()) {
            logger.info { "Checking exits for ${tradableOpen.size} open spread(s)" }
            coroutineScope {
                for (spread in tradableOpen) {
                    launch {
                        runCatching { checkSpreadExit(spread) }
                            .onFailure { e -> logger.error(e) { "[${spread.symbol}] Error checking spread exit: ${e.message}" } }
                    }
                }
            }
        }

        if (tradableClosing.isNotEmpty()) {
            logger.info { "Retrying close for ${tradableClosing.size} stuck CLOSING spread(s)" }
            coroutineScope {
                for (spread in tradableClosing) {
                    launch {
                        runCatching { retryClose(spread) }
                            .onFailure { e -> logger.error(e) { "[${spread.symbol}] Error retrying close: ${e.message}" } }
                    }
                }
            }
        }
    }

    private suspend fun retryClose(spread: Spread) {
        // A stuck CLOSING spread must still be closed (it's already mid-exit), so we don't skip
        // when quotes are missing — we just don't re-evaluate TP/SL from synthetic prices (H3).
        // Prefer the held per-leg stream (no farm churn); fall back to a one-off snapshot when the
        // stream is missing or stale so exit evaluation is never worse than before (2026-07-21).
        val soldMidLive =
            marketDataPort.streamedOptionMid(spread.soldLeg.contract)
                ?: marketDataPort.getOptionMidLive(spread.soldLeg.contract)
        val boughtMidLive =
            marketDataPort.streamedOptionMid(spread.boughtLeg.contract)
                ?: marketDataPort.getOptionMidLive(spread.boughtLeg.contract)
        val currentSpreadValue =
            if (soldMidLive != null && boughtMidLive != null) {
                soldMidLive.amount.subtract(boughtMidLive.amount)
            } else {
                null
            }
        val exitContext = captureExitContext(spread)

        // Re-evaluate against current market price — a stop-loss that sat in CLOSING while the
        // market recovered should close as PROFIT, not STOP. Without a live quote, preserve the
        // originally intended close status instead of inventing one.
        val inst = universePort.get(spread.symbol)
        val strat = strategyParams.forStrategy(spread.strategyId)
        val takeProfitPercent = inst?.takeProfitPercent ?: strat.takeProfitPercent
        val stopLossPercent = inst?.stopLossPercent ?: strat.stopLossPercent
        val tpThreshold = spread.creditPerShare.multiply(BigDecimal.ONE.subtract(BigDecimal(takeProfitPercent)))
        // SL basis is the entry MID, mirroring checkSpreadExit (see comment there; null = disabled).
        val slBasis = spread.entryMidPerShare ?: spread.creditPerShare
        val slThreshold =
            if (stopLossPercent > 0.0) slBasis.add(slBasis.multiply(BigDecimal(stopLossPercent))) else null

        val originalStatus =
            spread.closeReason
                ?.runCatching { SpreadStatus.valueOf(this) }
                ?.getOrNull()
                ?.takeIf { it != SpreadStatus.CLOSING }
                ?: SpreadStatus.CLOSED_MANUAL
        val actualStatus =
            when {
                currentSpreadValue == null -> originalStatus
                currentSpreadValue <= tpThreshold -> SpreadStatus.CLOSED_PROFIT
                slThreshold != null && currentSpreadValue >= slThreshold -> SpreadStatus.CLOSED_STOP
                // Between the thresholds, a preserved PROFIT/STOP intent can contradict the realized
                // sign (an AVGO spread closed −$263 as CLOSED_PROFIT this way) — relabel by whether
                // the buy-back costs more than the credit received. TIME/MANUAL intents stay: they
                // describe WHY the close happened, not its sign.
                originalStatus == SpreadStatus.CLOSED_PROFIT || originalStatus == SpreadStatus.CLOSED_STOP ->
                    if (currentSpreadValue > spread.creditPerShare) SpreadStatus.CLOSED_STOP else SpreadStatus.CLOSED_PROFIT
                else -> originalStatus
            }

        val recordedValue = currentSpreadValue ?: spread.lastSpreadValue ?: BigDecimal.ZERO
        logger.info { "[${spread.symbol}] Market-closing stuck CLOSING spread (target=$actualStatus, value=\$$recordedValue)" }
        forceCloseSpread(spread, recordedValue, actualStatus, actualStatus.name, exitContext)

        if (actualStatus == SpreadStatus.CLOSED_STOP) {
            executionPort.blockEntry(spread.symbol, Duration.ofHours(config.stopLossCooldownHours))
        }
    }

    private suspend fun checkSpreadExit(spread: Spread) {
        if (spread.id?.let { activeComboCloses.containsKey(it) } == true) {
            logger.debug { "[${spread.symbol}] Combo close in flight — skipping exit evaluation this cycle" }
            return
        }
        val today = LocalDate.now(clock)
        val expiry = spread.soldLeg.contract.expiry
        val dte = ChronoUnit.DAYS.between(today, expiry).toInt()

        // Fetch current mid-prices for both legs from LIVE quotes only (H3). Price-based exits
        // (take-profit / stop-loss) must not fire on synthetic Black-Scholes or previous-day data;
        // null means no live quote, so we evaluate only the price-independent time exit this cycle.
        // Prefer the held per-leg stream (no farm churn); fall back to a one-off snapshot when the
        // stream is missing or stale so exit evaluation is never worse than before (2026-07-21).
        val soldMidLive =
            marketDataPort.streamedOptionMid(spread.soldLeg.contract)
                ?: marketDataPort.getOptionMidLive(spread.soldLeg.contract)
        val boughtMidLive =
            marketDataPort.streamedOptionMid(spread.boughtLeg.contract)
                ?: marketDataPort.getOptionMidLive(spread.boughtLeg.contract)
        val currentSpreadValue =
            if (soldMidLive != null && boughtMidLive != null) {
                soldMidLive.amount.subtract(boughtMidLive.amount)
            } else {
                null
            }

        // Phase 1: Monitor quote freshness (missing quotes indicate potential staleness)
        // TODO: enhance with actual timestamp tracking from IbkrMarketDataRegistry in Phase 2
        val quoteStatus = if (soldMidLive != null && boughtMidLive != null) "LIVE" else "BLIND"
        if (quoteStatus == "BLIND") {
            logger.warn {
                "[${spread.symbol}] Quote health BLIND — missing live quotes for exit evaluation " +
                    "(soldLive=${soldMidLive != null} boughtLive=${boughtMidLive != null})"
            }
        }

        val inst = universePort.get(spread.symbol)
        val strat = strategyParams.forStrategy(spread.strategyId)
        val takeProfitPercent = inst?.takeProfitPercent ?: strat.takeProfitPercent
        val stopLossPercent = inst?.stopLossPercent ?: strat.stopLossPercent
        val timeProfitDte = inst?.timeProfitDte ?: strat.timeProfitDte

        // TP is anchored to the FILL credit (locking in a fraction of what was actually received);
        // SL is anchored to the ENTRY MID (fair value at fill). Anchoring the stop to the fill
        // credit let a below-mid fill mechanically tighten the stop — a spread filled at 50% of
        // mid with a credit-multiple stop starts life already stopped out (LITE, NBIS 2026-07-06/07).
        // Rows persisted before entry mids were recorded fall back to the credit.
        //
        // stopLossPercent <= 0 DISABLES the value stop (2026-07-18): a value stop fires on vol
        // expansion and option-mid noise (TXN was stopped 2026-07-17 with the underlying still ABOVE
        // the short strike). Losses are cut instead by the underlying stop below, which watches spot
        // against the short strike and is immune to that noise.
        val tpThreshold = spread.creditPerShare.multiply(BigDecimal.ONE.subtract(BigDecimal(takeProfitPercent)))
        val slBasis = spread.entryMidPerShare ?: spread.creditPerShare
        val slThreshold =
            if (stopLossPercent > 0.0) slBasis.add(slBasis.multiply(BigDecimal(stopLossPercent))) else null

        logger.debug {
            "[${spread.symbol}] spread value=\$${currentSpreadValue ?: "n/a"} credit=\$${"%.4f".format(spread.creditPerShare)} " +
                "TP≤\$${"%.4f".format(tpThreshold)} SL≥${slThreshold?.let { "\$${"%.4f".format(it)}" } ?: "disabled"} " +
                "(basis=\$${"%.4f".format(slBasis)}) DTE=$dte quote=$quoteStatus"
        }

        val valueStopBreached = slThreshold != null && currentSpreadValue != null && currentSpreadValue >= slThreshold

        // Underlying stop (2026-10-28): the replacement for "no stop at all". HON 230/220 broke its
        // short strike on 2026-08-11 with 38 DTE left and slid to a near-full-width loss while nothing
        // reacted — the 21-DTE exit came 17 days later with spot at 217. Spot is read from the held
        // legs' option-computation ticks (free); the snapshot fallback only runs when this stop is on.
        val underlyingNow =
            marketDataPort.streamedUnderlyingPrice(spread.symbol)?.amount
                ?: if (strat.underlyingStopEnabled) {
                    runCatching { marketDataPort.getUnderlyingPrice(spread.symbol).amount }.getOrNull()
                } else {
                    null
                }
        val underlyingStopTrigger =
            if (strat.underlyingStopEnabled) underlyingStopTrigger(spread, strat.underlyingStopBufferPct) else null
        val underlyingStopBreached =
            underlyingStopTrigger != null &&
                underlyingNow != null &&
                underlyingStopBreached(underlyingNow, underlyingStopTrigger, spread.soldLeg.contract.type)
        val slBreached = valueStopBreached || underlyingStopBreached
        if ((currentSpreadValue != null || underlyingNow != null) && !slBreached) {
            // Breaches must be CONSECUTIVE across VALUED observations — a real quote below the
            // threshold resets the count. A BLIND cycle (null value) is no information, not an
            // all-clear: resetting on it let flapping quotes starve the 2-cycle confirmation so the
            // stop could never fire (2026-07-09, ~80% BLIND sweeps).
            spread.id?.let { slBreachCounts.remove(it) }
        }

        val genericExit: Pair<SpreadStatus, String>? =
            when {
                currentSpreadValue != null && currentSpreadValue <= tpThreshold ->
                    SpreadStatus.CLOSED_PROFIT to "TP: spread value \$$currentSpreadValue ≤ \$$tpThreshold"
                slBreached && stopLossConfirmed(spread) ->
                    SpreadStatus.CLOSED_STOP to
                        if (underlyingStopBreached) {
                            "SL: underlying \$$underlyingNow through short strike " +
                                "${spread.soldLeg.contract.strike} (trigger \$$underlyingStopTrigger)"
                        } else {
                            "SL: spread value \$$currentSpreadValue ≥ \$$slThreshold"
                        }
                dte <= timeProfitDte ->
                    SpreadStatus.CLOSED_TIME to "DTE: $dte ≤ $timeProfitDte"
                else -> null
            }

        // Strategy seam: after the shared TP/SL/DTE rules, let the owning strategy add its own exit
        // (e.g. bear-call dividend-assignment protection). Bull put contributes none. The registry
        // dispatches by spread.strategyId, so the core stays free of per-strategy branches.
        val exitSignal: Pair<SpreadStatus, String>? =
            genericExit
                ?: strategyRegistry
                    .forSpread(spread)
                    ?.strategyExitSignal(spread)
                    ?.let { it.status to it.reason }

        if (exitSignal == null) {
            if (currentSpreadValue != null) {
                // Capture the underlying spot in the same cycle so the API can show a current price
                // and distance-to-short-strike for open positions. Non-fatal: a missing spot keeps
                // the prior stored value rather than blocking the value update.
                // Prefer the underlying IBKR already ships on the held legs' option-computation
                // ticks — this is display/telemetry only, and reading it costs no market-data line,
                // where the snapshot fallback burns one subscribe→cancel per position per cycle.
                val spot =
                    underlyingNow
                        ?: runCatching { marketDataPort.getUnderlyingPrice(spread.symbol).amount }.getOrNull()
                closers.forSpread(spread).recordLastValue(spread, currentSpreadValue, spot)
            } else {
                logger.debug { "[${spread.symbol}] No live option quotes — skipping price-based exit checks this cycle (DTE=$dte)" }
            }
            return
        }

        val (closeStatus, reason) = exitSignal
        val exitContext = captureExitContext(spread)
        spread.id?.let { slBreachCounts.remove(it) }

        // Broker check before ANY close order (2026-11-27 review). The leg paths buy the short back and
        // sell the long unconditionally — on a spread that is already flat at the broker (e.g. a close
        // that filled while the engine was down) that OPENS the reverse spread. Both legs flat → trade
        // nothing; reconciliation alerts the DB/broker mismatch. One leg flat → close only the held leg
        // (forceCloseSpread skips flat legs). null = positions unreadable → unchanged behaviour.
        when (val held = legsStillHeld(spread)) {
            Pair(false, false) -> {
                logger.warn {
                    "[${spread.symbol}] Exit signal ($reason) but BOTH legs are flat at the broker — placing no order; " +
                        "reconciliation will flag the DB/broker mismatch"
                }
                tradeLogger.info { "EXIT_SKIPPED ${spread.symbol}  ${spread.legsLabel}  reason=both_legs_flat_at_broker  signal=$reason" }
                return
            }
            null, Pair(true, true) -> Unit
            else -> {
                logger.warn { "[${spread.symbol}] Exit signal ($reason) with only one leg held ($held) — closing the held leg only" }
                val recordedValue = currentSpreadValue ?: spread.lastSpreadValue ?: BigDecimal.ZERO
                forceCloseSpread(spread, recordedValue, closeStatus, reason, exitContext)
                if (closeStatus == SpreadStatus.CLOSED_STOP) {
                    executionPort.blockEntry(spread.symbol, Duration.ofHours(config.stopLossCooldownHours))
                }
                return
            }
        }

        // Exit routing by urgency:
        //  - TP / time exits are not urgent — chase a limit at the MID to capture the edge a market
        //    order would give away (closeSpread leaves the spread CLOSING and retryClose escalates
        //    next cycle if the chase fails).
        //  - Stop-loss closes at a MARKETABLE limit (buy the short back at its ask, sell the long at
        //    its bid): fills immediately against a normal book but is bounded in a blown-out one —
        //    the raw MKT exits crossed junk-wide books (NBIS recorded $3.575, broker filled $3.95).
        //    If the chase fails, retryClose escalates to market within one monitor cycle, so
        //    certainty of execution is preserved.
        //  - Strategy-specific exits (bear-call dividend-assignment protection) and any exit with
        //    no usable NBBO go straight to market: certainty over price.
        val tickPort = marketTickPort
        val stopNbbo =
            if (closeStatus == SpreadStatus.CLOSED_STOP && tickPort != null) {
                runCatching {
                    withTimeout(3_000L.milliseconds) {
                        tickPort.streamSpreadCredit(spread.soldLeg.contract, spread.boughtLeg.contract).first()
                    }
                }.getOrNull()?.takeIf { it.soldAsk > 0.0 && it.boughtBid > 0.0 }
            } else {
                null
            }
        if (soldMidLive != null &&
            boughtMidLive != null &&
            (closeStatus == SpreadStatus.CLOSED_PROFIT || closeStatus == SpreadStatus.CLOSED_TIME)
        ) {
            if (comboCloseEligible(spread, dte)) {
                startComboClose(spread, closeStatus, reason, exitContext)
                return
            }
            logger.info { "[${spread.symbol}] Closing spread via limit chase — $reason" }
            val spreadValue = soldMidLive.amount.subtract(boughtMidLive.amount)
            closeSpread(spread, closeStatus, soldMidLive, boughtMidLive, spreadValue, exitContext, closeReason = reason)
        } else if (closeStatus == SpreadStatus.CLOSED_STOP &&
            stopNbbo != null &&
            comboCloseEligible(spread, Int.MAX_VALUE) &&
            comboClose(spread, closeStatus, reason, exitContext, stop = true)
        ) {
            // Closed atomically by a marketable combo (2026-11-27); otherwise fall through to the legs.
            logger.info { "[${spread.symbol}] Stop closed via marketable COMBO" }
        } else if (closeStatus == SpreadStatus.CLOSED_STOP && stopNbbo != null) {
            val buyBackLimit = Money(stopNbbo.soldAsk.toBigDecimal().setScale(4, RoundingMode.HALF_UP))
            val sellBackLimit = Money(stopNbbo.boughtBid.toBigDecimal().setScale(4, RoundingMode.HALF_UP))
            logger.info {
                "[${spread.symbol}] Closing spread via marketable limits (buy@ask=\$${buyBackLimit.amount} " +
                    "sell@bid=\$${sellBackLimit.amount}) — $reason"
            }
            val recordedValue = currentSpreadValue ?: spread.lastSpreadValue ?: BigDecimal.ZERO
            closeSpread(spread, closeStatus, buyBackLimit, sellBackLimit, recordedValue, exitContext, closeReason = reason)
        } else if (closeStatus == SpreadStatus.CLOSED_TIME && config.exitComboEnabled && dte > config.exitMarketFallbackDte) {
            // A time exit is not urgent: without live quotes, wait for them instead of sending blind
            // market orders (2026-11-27). Within exitMarketFallbackDte of expiry certainty wins.
            logger.info { "[${spread.symbol}] TIME exit due ($reason) but no live quotes — waiting, no blind market order" }
            return
        } else {
            // Time exit may fire without a live quote; record the last known spread value for P&L.
            val recordedValue = currentSpreadValue ?: spread.lastSpreadValue ?: BigDecimal.ZERO
            logger.info { "[${spread.symbol}] Closing spread at market — $reason" }
            forceCloseSpread(spread, recordedValue, closeStatus, reason, exitContext)
        }

        if (closeStatus == SpreadStatus.CLOSED_STOP) {
            executionPort.blockEntry(spread.symbol, Duration.ofHours(config.stopLossCooldownHours))
        }
    }

    private fun underlyingStopTrigger(
        spread: Spread,
        bufferPct: Double,
    ): BigDecimal = underlyingStopTrigger(spread.soldLeg.contract.strike, spread.soldLeg.contract.type, bufferPct)

    /**
     * Quality gates a breaching stop-loss observation must pass before it may close a position:
     *
     * 1. Entry grace — no SL in the first [ScannerConfig.stopLossGraceMinutesAfterEntry] minutes
     *    after the fill: quotes around a fresh fill are still settling and LITE was stopped 29 s
     *    after entry with the underlying flat.
     * 2. Opening rotation — no SL in the first [ScannerConfig.stopLossSkipFirstRthMinutes] minutes
     *    of the exchange session: opening books are junk-wide (NBIS was stopped at 9:59 ET on an
     *    opening-width mid while the underlying was UP).
     * 3. Hysteresis — the breach must persist [ScannerConfig.stopLossConfirmCycles] CONSECUTIVE
     *    monitor cycles (~1 min apart); the caller resets the count on any non-breaching cycle.
     *
     * TP and DTE exits are not gated: they are non-urgent limit chases, not market-outs, so a
     * false positive costs nothing irreversible. A genuinely crashing position still exits — one
     * extra cycle (~1 min) after grace, which raw market data noise cannot distinguish from anyway.
     */
    private fun stopLossConfirmed(spread: Spread): Boolean {
        // `in 0 until …`: a negative age (clock skew / replay) must not silently disable the stop.
        val minutesSinceEntry = Duration.between(spread.openedAt, Instant.now(clock)).toMinutes()
        if (minutesSinceEntry in 0 until config.stopLossGraceMinutesAfterEntry) {
            logger.warn {
                "[${spread.symbol}] SL breach ignored — entry grace period " +
                    "($minutesSinceEntry min since fill < ${config.stopLossGraceMinutesAfterEntry} min)"
            }
            return false
        }

        // Any failure to resolve the schedule means "no gate": an unknown session must never
        // suppress a stop.
        val inOpeningRotation =
            runCatching {
                val schedule = universePort.getMarketSchedule(spread.symbol)
                val nowAtExchange = LocalTime.now(clock.withZone(schedule.zone))
                val minutesSinceOpen = Duration.between(schedule.open, nowAtExchange).toMinutes()
                minutesSinceOpen in 0 until config.stopLossSkipFirstRthMinutes
            }.getOrDefault(false)
        if (inOpeningRotation) {
            logger.warn {
                "[${spread.symbol}] SL breach ignored — opening rotation " +
                    "(first ${config.stopLossSkipFirstRthMinutes} min of the session)"
            }
            return false
        }

        val id = spread.id ?: return true
        val breaches = slBreachCounts.merge(id, 1, Int::plus) ?: 1
        if (breaches < config.stopLossConfirmCycles) {
            logger.warn {
                "[${spread.symbol}] SL breach $breaches/${config.stopLossConfirmCycles} — awaiting consecutive confirmation"
            }
            return false
        }
        return true
    }

    private suspend fun captureExitContext(spread: Spread): Pair<BigDecimal?, BigDecimal?> {
        val underlyingPrice = runCatching { marketDataPort.getUnderlyingPrice(spread.symbol).amount }.getOrNull()
        val ivRank =
            runCatching {
                volatilityPort
                    .getIvRank(
                        spread.symbol,
                    ).rank
                    .toBigDecimal()
                    .setScale(2, RoundingMode.HALF_UP)
            }.getOrNull()
        return underlyingPrice to ivRank
    }

    // -------------------------------------------------------------------------
    // Combo exit (2026-11-26)
    // -------------------------------------------------------------------------

    private suspend fun comboCloseEligible(
        spread: Spread,
        dte: Int,
    ): Boolean {
        val port = orderExecutionPort ?: return false
        if (!config.exitComboEnabled || marketTickPort == null) return false
        // Near expiry certainty beats price: keep the legacy path, which escalates to market.
        if (dte <= config.exitMarketFallbackDte) return false
        if (!port.supportsComboClose(spread.symbol)) return false
        // A combo close reverses BOTH legs — if one is already flat (or positions can't be read) it
        // could open a fresh position on that leg, so only a confirmed two-leg spread goes combo.
        val held = legsStillHeld(spread)
        if (held != Pair(true, true)) {
            logger.info { "[${spread.symbol}] Combo close skipped: legs held=$held — using leg-by-leg close" }
            return false
        }
        return true
    }

    private suspend fun startComboClose(
        spread: Spread,
        closeStatus: SpreadStatus,
        reason: String,
        exitContext: Pair<BigDecimal?, BigDecimal?>,
    ) {
        val id = spread.id
        val scope = closeScope
        if (scope == null || id == null) {
            comboClose(spread, closeStatus, reason, exitContext)
            return
        }
        if (activeComboCloses.containsKey(id)) return
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    comboClose(spread, closeStatus, reason, exitContext)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "[${spread.symbol}] Combo close crashed: ${e.message}" }
                } finally {
                    activeComboCloses.remove(id)
                }
            }
        if (activeComboCloses.putIfAbsent(id, job) == null) job.start() else job.cancel()
    }

    /**
     * Close a spread with ONE combo order: start part-way from mid toward the best debit, step up on
     * the timer to a ceiling, rest there until the timeout. Filled → spread closed at the fill price.
     * Not filled → order cancelled, spread stays OPEN, the next monitor cycle tries again. Never goes
     * to market (that is reserved for stops and the last days before expiry).
     */
    private suspend fun comboClose(
        spread: Spread,
        closeStatus: SpreadStatus,
        reason: String,
        exitContext: Pair<BigDecimal?, BigDecimal?>,
        // Stop mode: one marketable combo at the natural debit (soldAsk − boughtBid), short timeout;
        // the caller falls back to the leg path if it does not fill. Returns true when filled.
        stop: Boolean = false,
    ): Boolean {
        val port = orderExecutionPort ?: return false
        val tickPort = marketTickPort ?: return false
        val sold = spread.soldLeg.contract
        val bought = spread.boughtLeg.contract
        // A close order from an earlier attempt (e.g. before a restart) may still be working; a second
        // one could fill too and open the reverse spread.
        if (port.hasWorkingOptionOrders(spread.symbol)) {
            logger.warn { "[${spread.symbol}] Combo close deferred: an option order is still working at the broker" }
            return false
        }
        val nbbo =
            runCatching {
                withTimeout(3_000L.milliseconds) { tickPort.streamSpreadCredit(sold, bought).first() }
            }.getOrNull()?.takeIf { it.soldAsk > 0.0 && it.boughtAsk > 0.0 && it.soldBid >= 0.0 && it.boughtBid >= 0.0 }
        if (nbbo == null) {
            logger.info { "[${spread.symbol}] Combo close deferred: no usable NBBO — stays OPEN, retry next cycle" }
            return false
        }
        val minPrice = BigDecimal("0.01")
        val mid =
            ((nbbo.soldBid + nbbo.soldAsk) / 2.0 - (nbbo.boughtBid + nbbo.boughtAsk) / 2.0)
                .toBigDecimal()
                .setScale(4, RoundingMode.HALF_UP)
        val best = (nbbo.soldBid - nbbo.boughtAsk).toBigDecimal().setScale(4, RoundingMode.HALF_UP)
        val midPrice = mid.max(minPrice)
        val ceiling =
            midPrice
                .multiply(BigDecimal(config.exitMaxPayPctOfMid))
                .max(midPrice.add(optionTickFor(midPrice)))
                .ceilToOptionTick()
        val natural = (nbbo.soldAsk - nbbo.boughtBid).toBigDecimal().setScale(4, RoundingMode.HALF_UP)
        val pct = BigDecimal(config.exitStartPctTowardBest.coerceIn(0.0, 1.0))
        val rawStart = if (best < mid) mid.subtract(mid.subtract(best).multiply(pct)) else mid
        val start =
            if (stop) {
                natural.max(minPrice).ceilToOptionTick().max(ceiling)
            } else {
                rawStart.floorToOptionTick().max(minPrice).min(ceiling)
            }
        val timeoutMs = if (stop) config.exitStopComboSeconds * 1_000L else config.exitTimeoutMinutes * 60_000L
        val intervalMs = config.priceAdjustIntervalSeconds.coerceAtLeast(1) * 1_000L
        val steps = (config.exitLadderMinutes * 60 / config.priceAdjustIntervalSeconds.coerceAtLeast(1)).coerceAtLeast(1)
        val stepSize = ceiling.subtract(start).max(BigDecimal.ZERO).divide(BigDecimal(steps), 4, RoundingMode.UP)

        var price = start
        val orderId =
            runCatching { port.placeComboCloseOrder(null, sold, bought, Money(price), spread.quantity) }
                .getOrElse { e ->
                    logger.warn(e) { "[${spread.symbol}] Combo close submit failed — stays OPEN, retry next cycle" }
                    return false
                }
        logger.info {
            "[${spread.symbol}] Closing spread via COMBO — $reason: start=\$$price mid=\$$mid best=\$$best " +
                "ceiling=\$$ceiling orderId=$orderId"
        }

        val terminal: OrderStatus? =
            withTimeoutOrNull(timeoutMs.milliseconds) {
                coroutineScope {
                    val fill = async { runCatching { port.awaitFill(orderId) }.getOrNull() }
                    var observed: OrderStatus? = null
                    while (observed == null) {
                        val st =
                            if (fill.isCompleted) {
                                delay(intervalMs.milliseconds)
                                fill.await()
                            } else {
                                withTimeoutOrNull(intervalMs.milliseconds) { fill.await() }
                            }
                        if (st != null && st != OrderStatus.PENDING) {
                            observed = st
                        } else if (price < ceiling) {
                            val tick = optionTickFor(price)
                            val step = stepSize.divide(tick, 0, RoundingMode.UP).multiply(tick).max(tick)
                            val next = price.add(step).min(ceiling)
                            runCatching { port.placeComboCloseOrder(orderId, sold, bought, Money(next), spread.quantity) }
                                .onSuccess { price = next }
                                .onFailure { e -> logger.warn(e) { "[${spread.symbol}] Combo close amend failed — keeping \$$price" } }
                        }
                    }
                    fill.cancel()
                    observed
                }
            }
        val outcome =
            terminal ?: runCatching { port.cancelAndAwait(orderId) }
                .onFailure { e -> logger.warn(e) { "[${spread.symbol}] Combo close cancel failed for order $orderId" } }
                .getOrNull()

        if (outcome != OrderStatus.FILLED) {
            logger.info {
                "[${spread.symbol}] Combo close NOT filled (status=${outcome ?: "unknown"}, last=\$$price, mid=\$$mid) — " +
                    "stays OPEN, retry next cycle"
            }
            tradeLogger.info {
                "EXIT_UNFILLED ${spread.symbol}  ${spread.legsLabel}  reason=$reason  last=\$$price  mid=\$$mid" +
                    "  ceiling=\$$ceiling  order=$orderId  " + if (stop) "(falling back to leg close)" else "(stays OPEN)"
            }
            return false
        }

        val reported = port.consumeFillPrice(orderId)?.abs()
        val closePrice =
            if (reported != null && reported <= ceiling.max(start).add(BigDecimal("0.10"))) reported else price
        val current = spread.id?.let { closers.findById(it) } ?: spread
        closers.forSpread(current).close(current, closeStatus, reason, closePrice, exitContext.first, exitContext.second)
        logger.info { "[${spread.symbol}] Spread closed via COMBO: $closeStatus at \$$closePrice (mid \$$mid, order $orderId)" }
        tradeLogger.info {
            "EXIT   ${spread.symbol}  ${spread.legsLabel}" +
                "  exp=${sold.expiry}  reason=$reason  value=\$$closePrice  mid=\$$mid  (combo)" +
                "  credit=\$${spread.creditPerShare}  pnl=\$${spread.creditPerShare.subtract(
                    closePrice,
                ).multiply(config.contractMultiplier).multiply(BigDecimal(spread.quantity))}"
        }
        return true
    }

    /**
     * Net debit actually paid to close, per share: buy-back fill - sell-back fill, from the broker's
     * reported average fill prices. Null when either fill price is unknown (e.g. a leg was already
     * flat and skipped) - the caller then falls back to its pre-order estimate. Before 2026-11-27 every
     * leg-path close recorded that pre-order mid, so stop/market P&L looked better than the fills.
     */
    private fun realizedCloseValue(
        spread: Spread,
        buyOrderId: Int?,
        sellOrderId: Int?,
    ): BigDecimal? {
        val buy = buyOrderId?.let { id -> runCatching { orderPort.consumeFillPrice(id) }.getOrNull() }
        val sell = sellOrderId?.let { id -> runCatching { orderPort.consumeFillPrice(id) }.getOrNull() }
        if (buy == null || sell == null) {
            logger.info { "[${spread.symbol}] Close fill prices incomplete (buy=$buy sell=$sell) - recording the pre-order estimate" }
            return null
        }
        return buy.abs().subtract(sell.abs()).setScale(4, RoundingMode.HALF_UP)
    }

    private suspend fun closeSpread(
        spread: Spread,
        closeStatus: SpreadStatus,
        soldMid: Money,
        boughtMid: Money,
        currentSpreadValue: BigDecimal,
        exitContext: Pair<BigDecimal?, BigDecimal?> = Pair(null, null),
        closeReason: String = closeStatus.name,
    ) {
        val roundedSoldMid = Money(soldMid.amount.roundToOptionTick())
        val roundedBoughtMid = Money(boughtMid.amount.roundToOptionTick())

        // Mark CLOSING immediately so the scanner won't re-enter this symbol while orders are in flight
        val closer = closers.forSpread(spread)
        val closing = closer.markClosing(spread, closeStatus, currentSpreadValue)

        // Sold leg is priced at zero — options are effectively worthless. Placing a buy limit at $0.00
        // will never fill. Skip the buy-back and close directly.
        if (roundedSoldMid.amount <= BigDecimal.ZERO) {
            // M1: a zero can mean "no market data" rather than "genuinely worthless". Finalize the
            // close only when the $0 is confirmed real — either a LIVE quote confirms it, or the broker
            // affirms the short leg is flat (worthless / expired / already bought back). If neither
            // confirms, leave it CLOSING so the monitor retries — never phantom-close a live position
            // during a data outage. Without the broker check a manual close placed while market data is
            // down strands in CLOSING forever (never reaching a terminal status), so it also never
            // surfaces in analytics.
            val liveConfirmsWorthless = marketDataPort.getOptionMidLive(spread.soldLeg.contract) != null
            // legsStillHeld returns null when positions can't be queried (stay CLOSING, safe); the short
            // leg is `.first` — false means the broker affirmatively reports it flat.
            val brokerConfirmsShortFlat = legsStillHeld(spread)?.first == false
            if (!liveConfirmsWorthless && !brokerConfirmsShortFlat) {
                logger.warn {
                    "[${spread.symbol}] Sold leg priced \$0 but neither a live quote nor a flat broker " +
                        "position confirms it — leaving CLOSING (not marking worthless)"
                }
                return
            }
            closer.close(closing, closeStatus, closeReason, currentSpreadValue, exitContext.first, exitContext.second)
            logger.info {
                "[${spread.symbol}] Sold leg worthless (${if (liveConfirmsWorthless) "live quote" else "broker flat"}) " +
                    "— closed $closeStatus without buy-back"
            }
            tradeLogger.info {
                "EXIT   ${spread.symbol}  ${spread.legsLabel}" +
                    "  exp=${spread.soldLeg.contract.expiry}  reason=$closeReason  value=\$0 (worthless)" +
                    "  credit=\$${spread.creditPerShare}  pnl=\$${spread.creditPerShare.multiply(
                        config.contractMultiplier,
                    ).multiply(BigDecimal(spread.quantity))}"
            }
            return
        }

        val buyBackOrder =
            orderPort.placeAndAwaitFill(
                contract = spread.soldLeg.contract,
                action = LegAction.BUY,
                limitPrice = roundedSoldMid,
                qty = spread.quantity,
            )
        if (buyBackOrder.status != OrderStatus.FILLED) {
            logger.warn {
                "[${spread.symbol}] Buy-back of sold put did not fill after chase — left as CLOSING; use Kill button or manual close"
            }
            tradeLogger.info {
                "CLOSING ${spread.symbol}  ${spread.legsLabel}  reason=buy_back_unfilled"
            }
            return
        }

        val sellBackOrder =
            orderPort.placeAndAwaitFill(
                contract = spread.boughtLeg.contract,
                action = LegAction.SELL,
                limitPrice = roundedBoughtMid,
                qty = spread.quantity,
            )
        if (sellBackOrder.status != OrderStatus.FILLED) {
            // The short leg is already bought back (flat); the long leg's sell-back just didn't fill.
            // The residual position is a paid-for long option — a bounded debit, never unhedged risk
            // — so finish the close by selling it at market rather than re-establishing the short.
            logger.warn {
                "[${spread.symbol}] Sell-back of bought leg did not fill after chase — retrying at market " +
                    "(residual position is a bounded long, not unhedged)"
            }

            val marketSellBack =
                runCatching {
                    orderPort.placeMarketOrder(
                        contract = spread.boughtLeg.contract,
                        action = LegAction.SELL,
                        qty = spread.quantity,
                    )
                }.onFailure { e ->
                    logger.warn(e) {
                        "[${spread.symbol}] Market sell-back of bought leg failed — left as CLOSING; " +
                            "retryClose will attempt again next monitor cycle"
                    }
                }.getOrNull()

            if (marketSellBack?.status == OrderStatus.FILLED) {
                val closePrice = realizedCloseValue(spread, buyBackOrder.orderId, marketSellBack.orderId) ?: currentSpreadValue
                closer.close(closing, closeStatus, closeReason, closePrice, exitContext.first, exitContext.second)
                logger.info { "[${spread.symbol}] Spread closed: bought leg sold at market after limit chase failed" }
                tradeLogger.info {
                    "EXIT   ${spread.symbol}  ${spread.legsLabel}" +
                        "  exp=${spread.soldLeg.contract.expiry}  reason=$closeReason  value=\$$closePrice" +
                        "  (bought leg closed at market)"
                }
            } else {
                logger.warn {
                    "[${spread.symbol}] Bought leg still open (short already flat) — left as CLOSING; " +
                        "legsStillHeld will skip the already-flat short on the next retry"
                }
                tradeLogger.info {
                    "CLOSING ${spread.symbol}  ${spread.legsLabel}  reason=sell_back_unfilled"
                }
            }
            return
        }

        val closePrice = realizedCloseValue(spread, buyBackOrder.orderId, sellBackOrder.orderId) ?: currentSpreadValue
        closer.close(closing, closeStatus, closeReason, closePrice, exitContext.first, exitContext.second)
        logger.info { "[${spread.symbol}] Spread closed: $closeStatus at \$$closePrice (pre-order mid \$$currentSpreadValue)" }
        tradeLogger.info {
            "EXIT   ${spread.symbol}  ${spread.legsLabel}" +
                "  exp=${spread.soldLeg.contract.expiry}  reason=$closeReason  value=\$$closePrice" +
                "  credit=\$${spread.creditPerShare}  pnl=\$${spread.creditPerShare.subtract(
                    closePrice,
                ).multiply(config.contractMultiplier).multiply(BigDecimal(spread.quantity))}"
        }
    }
}
