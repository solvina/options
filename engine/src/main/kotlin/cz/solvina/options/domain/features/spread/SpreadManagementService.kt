package cz.solvina.options.domain.features.spread

import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.execution.TradeExecutionPort
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.market.MarketTickPort
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.OrderPort
import cz.solvina.options.domain.features.order.OrderStatus
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("TRADES")

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
) {
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

        // Issue #7: Track order IDs for cancellation if verification fails
        var soldOrderId: Int? = null
        var boughtOrderId: Int? = null

        // C5: only close legs that are still held in IBKR. On a retry after a partial close, the
        // leg that already filled shows flat here and is skipped — so we never fire a second
        // order on it (which would open an unintended opposite position). null = positions can't
        // be queried → close both, preserving the prior best-effort behaviour.
        val held = legsStillHeld(spread)
        val closeSold = held?.first ?: true
        val closeBought = held?.second ?: true

        if (closeSold) {
            runCatching { orderPort.placeMarketOrder(spread.soldLeg.contract, LegAction.BUY, spread.quantity) }
                .onSuccess { legOrder -> soldOrderId = legOrder.orderId }
                .onFailure { e ->
                    logger.warn { "[${spread.symbol}] Sold-leg market order timed out (order still submitted): ${e.message}" }
                }
        } else {
            logger.info { "[${spread.symbol}] Sold leg already flat — skipping buy-back (idempotent close)" }
        }

        if (closeBought) {
            runCatching { orderPort.placeMarketOrder(spread.boughtLeg.contract, LegAction.SELL, spread.quantity) }
                .onSuccess { legOrder -> boughtOrderId = legOrder.orderId }
                .onFailure { e ->
                    logger.warn { "[${spread.symbol}] Bought-leg market order timed out (order still submitted): ${e.message}" }
                }
        } else {
            logger.info { "[${spread.symbol}] Bought leg already flat — skipping sell-back (idempotent close)" }
        }

        // Verify position: check that both legs are actually closed in IBKR before marking spread as CLOSED
        val positionVerified = verifyPositionClosed(spread)
        if (!positionVerified) {
            // Issue #7: Verification failed — cancel the orders we just placed to prevent duplicates on retry
            logger.warn {
                "[${spread.symbol}] Position verification FAILED — legs still open in IBKR. " +
                    "Cancelling orders placed in this attempt to prevent duplicates."
            }

            // Cancel both orders that were placed
            if (soldOrderId != null && soldOrderId!! > 0) {
                runCatching { orderPort.cancelOrder(soldOrderId!!) }
                    .onSuccess {
                        logger.info { "[${spread.symbol}] Cancelled sold-leg order $soldOrderId due to verification timeout" }
                    }.onFailure { e ->
                        logger.warn {
                            "[${spread.symbol}] Failed to cancel sold-leg order $soldOrderId: ${e.message}"
                        }
                    }
            }

            if (boughtOrderId != null && boughtOrderId!! > 0) {
                runCatching { orderPort.cancelOrder(boughtOrderId!!) }
                    .onSuccess {
                        logger.info { "[${spread.symbol}] Cancelled bought-leg order $boughtOrderId due to verification timeout" }
                    }.onFailure { e ->
                        logger.warn {
                            "[${spread.symbol}] Failed to cancel bought-leg order $boughtOrderId: ${e.message}"
                        }
                    }
            }

            // Keep spread in CLOSING state; next retryClose will try again
            return spread
        }

        val updated =
            closers.forSpread(spread).close(
                spread = spread,
                status = closeStatus,
                closeReason = closeReason,
                closePrice = estimatedSpreadValue,
                underlyingAtExit = exitContext.first,
                ivAtExit = exitContext.second,
            )
        logger.info { "[${spread.symbol}] Force-closed. status=$closeStatus value=\$$estimatedSpreadValue" }
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
        val soldMidLive = marketDataPort.getOptionMidLive(spread.soldLeg.contract)
        val boughtMidLive = marketDataPort.getOptionMidLive(spread.boughtLeg.contract)
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
        // SL basis is the entry MID, mirroring checkSpreadExit (see comment there).
        val slBasis = spread.entryMidPerShare ?: spread.creditPerShare
        val slThreshold = slBasis.add(slBasis.multiply(BigDecimal(stopLossPercent)))

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
                currentSpreadValue >= slThreshold -> SpreadStatus.CLOSED_STOP
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
        val today = LocalDate.now(clock)
        val expiry = spread.soldLeg.contract.expiry
        val dte = ChronoUnit.DAYS.between(today, expiry).toInt()

        // Fetch current mid-prices for both legs from LIVE quotes only (H3). Price-based exits
        // (take-profit / stop-loss) must not fire on synthetic Black-Scholes or previous-day data;
        // null means no live quote, so we evaluate only the price-independent time exit this cycle.
        val soldMidLive = marketDataPort.getOptionMidLive(spread.soldLeg.contract)
        val boughtMidLive = marketDataPort.getOptionMidLive(spread.boughtLeg.contract)
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
        val tpThreshold = spread.creditPerShare.multiply(BigDecimal.ONE.subtract(BigDecimal(takeProfitPercent)))
        val slBasis = spread.entryMidPerShare ?: spread.creditPerShare
        val slThreshold = slBasis.add(slBasis.multiply(BigDecimal(stopLossPercent)))

        logger.debug {
            "[${spread.symbol}] spread value=\$${currentSpreadValue ?: "n/a"} credit=\$${"%.4f".format(spread.creditPerShare)} " +
                "TP≤\$${"%.4f".format(tpThreshold)} SL≥\$${"%.4f".format(slThreshold)} " +
                "(basis=\$${"%.4f".format(slBasis)}) DTE=$dte quote=$quoteStatus"
        }

        val slBreached = currentSpreadValue != null && currentSpreadValue >= slThreshold
        if (!slBreached) {
            // Breaches must be CONSECUTIVE — any non-breaching observation resets the count.
            spread.id?.let { slBreachCounts.remove(it) }
        }

        val genericExit: Pair<SpreadStatus, String>? =
            when {
                currentSpreadValue != null && currentSpreadValue <= tpThreshold ->
                    SpreadStatus.CLOSED_PROFIT to "TP: spread value \$$currentSpreadValue ≤ \$$tpThreshold"
                slBreached && stopLossConfirmed(spread) ->
                    SpreadStatus.CLOSED_STOP to "SL: spread value \$$currentSpreadValue ≥ \$$slThreshold"
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
                closers.forSpread(spread).recordLastValue(spread, currentSpreadValue)
            } else {
                logger.debug { "[${spread.symbol}] No live option quotes — skipping price-based exit checks this cycle (DTE=$dte)" }
            }
            return
        }

        val (closeStatus, reason) = exitSignal
        val exitContext = captureExitContext(spread)
        spread.id?.let { slBreachCounts.remove(it) }

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
                    withTimeout(3_000L) {
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
            logger.info { "[${spread.symbol}] Closing spread via limit chase — $reason" }
            val spreadValue = soldMidLive.amount.subtract(boughtMidLive.amount)
            closeSpread(spread, closeStatus, soldMidLive, boughtMidLive, spreadValue, exitContext, closeReason = reason)
        } else if (closeStatus == SpreadStatus.CLOSED_STOP && stopNbbo != null) {
            val buyBackLimit = Money(stopNbbo.soldAsk.toBigDecimal().setScale(4, RoundingMode.HALF_UP))
            val sellBackLimit = Money(stopNbbo.boughtBid.toBigDecimal().setScale(4, RoundingMode.HALF_UP))
            logger.info {
                "[${spread.symbol}] Closing spread via marketable limits (buy@ask=\$${buyBackLimit.amount} " +
                    "sell@bid=\$${sellBackLimit.amount}) — $reason"
            }
            val recordedValue = currentSpreadValue ?: spread.lastSpreadValue ?: BigDecimal.ZERO
            closeSpread(spread, closeStatus, buyBackLimit, sellBackLimit, recordedValue, exitContext, closeReason = reason)
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
                closer.close(closing, closeStatus, closeReason, currentSpreadValue, exitContext.first, exitContext.second)
                logger.info { "[${spread.symbol}] Spread closed: bought leg sold at market after limit chase failed" }
                tradeLogger.info {
                    "EXIT   ${spread.symbol}  ${spread.legsLabel}" +
                        "  exp=${spread.soldLeg.contract.expiry}  reason=$closeReason  value=\$$currentSpreadValue" +
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

        closer.close(closing, closeStatus, closeReason, currentSpreadValue, exitContext.first, exitContext.second)
        logger.info { "[${spread.symbol}] Spread closed: $closeStatus at \$$currentSpreadValue" }
        tradeLogger.info {
            "EXIT   ${spread.symbol}  ${spread.legsLabel}" +
                "  exp=${spread.soldLeg.contract.expiry}  reason=$closeReason  value=\$$currentSpreadValue" +
                "  credit=\$${spread.creditPerShare}  pnl=\$${spread.creditPerShare.subtract(
                    currentSpreadValue,
                ).multiply(config.contractMultiplier).multiply(BigDecimal(spread.quantity))}"
        }
    }
}
