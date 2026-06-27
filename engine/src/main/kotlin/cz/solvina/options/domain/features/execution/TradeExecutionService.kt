package cz.solvina.options.domain.features.execution

import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.execution.model.TradeExecutionResult
import cz.solvina.options.domain.features.market.MarketTickPort
import cz.solvina.options.domain.features.market.SpreadCreditTick
import cz.solvina.options.domain.features.order.LegQuotes
import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.order.StrandedLongLegException
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.spread.BullPutSpreadPort
import cz.solvina.options.domain.features.spread.model.Spread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("TRADES")

@Service
class TradeExecutionService(
    private val marketTickPort: MarketTickPort,
    private val orderExecutionPort: OrderExecutionPort,
    // Bull-put port retained only for the active-spread cap count; Phase 4 replaces this with a
    // cross-strategy SpreadQueryFacade so the cap sums bull puts + bear calls.
    private val spreadPort: BullPutSpreadPort,
    private val writerRegistry: SpreadEntryWriterRegistry,
    private val validator: PreTradeValidator,
    private val config: ScannerConfig,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : TradeExecutionPort {
    private val inFlightSymbols = ConcurrentHashMap<Symbol, Unit>()
    private val cooldownUntil = ConcurrentHashMap<Symbol, Instant>()

    // Serialises the cap check + slot reservation so concurrent scans can't collectively
    // exceed maxOpenSpreads (C1). Held only for the count+reserve, never across order I/O.
    private val capMutex = Mutex()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    override suspend fun execute(request: TradeExecutionRequest): TradeExecutionResult {
        if (!config.tradingEnabled) {
            logger.info {
                "[${request.underlyingSymbol}] DIAGNOSTIC: would enter " +
                    "${request.soldContract.strike}P/${request.boughtContract.strike}P " +
                    "exp=${request.soldContract.expiry} " +
                    "credit≈\$${request.targetCredit} ivRank=${"%.1f".format(request.ivRankAtEntry)}%"
            }
            tradeLogger.info {
                "DIAGNOSTIC ${request.underlyingSymbol}  ${request.soldContract.strike}P/${request.boughtContract.strike}P" +
                    "  exp=${request.soldContract.expiry}  credit=\$${request.targetCredit}  iv_rank=${"%.1f".format(
                        request.ivRankAtEntry,
                    )}%"
            }
            return TradeExecutionResult(ExecutionOutcome.DIAGNOSTIC_SKIPPED)
        }

        // Pre-trade checks
        val preCheck = validator.validate(request, inFlightSymbols.keys)
        if (preCheck != null) return TradeExecutionResult(preCheck)

        // Atomic cap check + slot reservation (C1). Count established positions (OPEN+CLOSING)
        // from the DB plus the live in-flight set — each in-flight entry will become a PENDING
        // row — and reserve the slot under a single mutex so two concurrent scans can't both
        // pass the gate and overshoot maxOpenSpreads. The mutex is released before any order I/O.
        val reservation =
            capMutex.withLock {
                if (inFlightSymbols.containsKey(request.underlyingSymbol)) {
                    ExecutionOutcome.EXPOSURE_REJECTED
                } else {
                    val active =
                        spreadPort.countByStatus(SpreadStatus.OPEN) +
                            spreadPort.countByStatus(SpreadStatus.CLOSING) +
                            inFlightSymbols.size
                    if (active >= config.maxOpenSpreads) {
                        ExecutionOutcome.CAP_REACHED
                    } else {
                        inFlightSymbols[request.underlyingSymbol] = Unit
                        null
                    }
                }
            }
        if (reservation != null) {
            if (reservation == ExecutionOutcome.CAP_REACHED) {
                logger.info {
                    "[${request.underlyingSymbol}] CAP_REACHED — active spreads at maxOpenSpreads=${config.maxOpenSpreads}, skipping entry"
                }
                tradeLogger.info {
                    "REJECT ${request.underlyingSymbol}  ${request.soldContract.strike}P/${request.boughtContract.strike}P  reason=CAP_REACHED"
                }
            }
            return TradeExecutionResult(reservation)
        }

        return try {
            executeInternal(request)
        } finally {
            inFlightSymbols.remove(request.underlyingSymbol)
        }
    }

    override fun isInFlight(symbol: Symbol): Boolean = inFlightSymbols.containsKey(symbol)

    override fun isCoolingDown(symbol: Symbol): Boolean = cooldownUntil[symbol]?.let { Instant.now(clock).isBefore(it) } ?: false

    override fun blockEntry(
        symbol: Symbol,
        duration: Duration,
    ) {
        val until = Instant.now(clock).plus(duration)
        cooldownUntil[symbol] = until
        logger.info { "[$symbol] Entry blocked until $until (stop-loss cooldown ${duration.toHours()}h)" }
    }

    // -------------------------------------------------------------------------
    // Execution loop
    // -------------------------------------------------------------------------

    private suspend fun executeInternal(request: TradeExecutionRequest): TradeExecutionResult {
        logger.info {
            "[${request.underlyingSymbol}] Execute: target=\$${request.targetCredit} " +
                "floor=\$${request.floorCredit} " +
                "soldBid/ask=\$${request.soldBid}/\$${request.soldAsk} " +
                "boughtBid/ask=\$${request.boughtBid}/\$${request.boughtAsk}"
        }
        // Strategy-specific persistence (build + status writes) behind the SpreadEntryWriter seam,
        // resolved by request.strategyId — the execution loop itself stays strategy-agnostic.
        val writer = writerRegistry.forStrategy(request.strategyId)
        // Persist PENDING before submitting — survives engine restarts so fill can be recovered
        var pendingSpread: Spread = writer.persistPending(request, request.targetCredit)

        // Fetch fresh quotes immediately before submission and recalculate net credit
        val (freshCredit, stalePriceResult, freshTick) = calculateFreshCredit(request)
        if (stalePriceResult != null) {
            logger.info { "[${request.underlyingSymbol}] ${stalePriceResult.message}" }
            writer.markStatus(pendingSpread, SpreadStatus.CLOSED_TIMEOUT, stalePriceResult.outcome.name.lowercase())
            return TradeExecutionResult(stalePriceResult.outcome)
        }

        // Forward fresh per-leg NBBO so leg-by-leg exchanges (EUREX) price each leg off its own book.
        // Atomic-combo (US) exchanges ignore this and price off netCredit.
        val legQuotes =
            freshTick?.let {
                LegQuotes(
                    soldBid = it.soldBid.toBigDecimal(),
                    soldAsk = it.soldAsk.toBigDecimal(),
                    boughtBid = it.boughtBid.toBigDecimal(),
                    boughtAsk = it.boughtAsk.toBigDecimal(),
                )
            }

        var currentOrderId =
            runCatching {
                orderExecutionPort.submitComboLimitOrder(
                    soldContract = request.soldContract,
                    boughtContract = request.boughtContract,
                    netCredit = Money(freshCredit),
                    qty = request.quantity,
                    legQuotes = legQuotes,
                )
            }.getOrElse { e ->
                if (e is StrandedLongLegException) {
                    // Leg-by-leg: protective LONG filled but SHORT did not (auto-unwind off). A bounded
                    // long-debit position is open — never a naked short. Record it as BROKEN_LONG_ONLY
                    // (retaining the long order id) so it is tracked and surfaced for manual handling,
                    // instead of being silently swallowed as a generic rejection.
                    logger.error(e) {
                        "[${request.underlyingSymbol}] STRANDED LONG (longOrderId=${e.longOrderId}) — protective " +
                            "long filled, short did not; recording BROKEN_LONG_ONLY for manual handling"
                    }
                    writer.markBrokenLongOnly(pendingSpread, e.longOrderId, "broken_long_only: ${e.message}")
                    tradeLogger.warn {
                        "BROKEN_LONG_ONLY ${request.underlyingSymbol}  ${request.boughtContract.strike}P" +
                            "  long_order=${e.longOrderId}  reason=${e.message}"
                    }
                    return TradeExecutionResult(ExecutionOutcome.BROKEN_LONG_ONLY, comboOrderId = e.longOrderId)
                }
                logger.error(e) { "[${request.underlyingSymbol}] Failed to submit order" }
                writer.markStatus(pendingSpread, SpreadStatus.CLOSED_REJECTED, "order_rejected: ${e.message}")
                return TradeExecutionResult(ExecutionOutcome.ORDER_REJECTED)
            }
        // Stamp the real orderId now that we have it
        pendingSpread = writer.stampOrderIds(pendingSpread, currentOrderId, freshCredit)
        var currentCredit = freshCredit
        var ticksSinceAdjust = 0
        var outcome = ExecutionOutcome.TIMED_OUT

        logger.info {
            "[${request.underlyingSymbol}] Combo order submitted: " +
                "orderId=$currentOrderId credit=\$$currentCredit"
        }

        // Event channel — merges all event sources
        val eventChannel = Channel<ExecutionEvent>(Channel.UNLIMITED)

        val underlyingJob: Job =
            scope.launch {
                marketTickPort
                    .streamUnderlyingPrice(request.underlyingSymbol)
                    .catch { e -> logger.warn(e) { "[${request.underlyingSymbol}] Underlying stream error" } }
                    .collect { price -> eventChannel.trySend(ExecutionEvent.Underlying(price)) }
            }

        val creditJob: Job =
            scope.launch {
                marketTickPort
                    .streamSpreadCredit(request.soldContract, request.boughtContract)
                    .catch { e -> logger.warn(e) { "[${request.underlyingSymbol}] Credit stream error" } }
                    .collect { tick -> eventChannel.trySend(ExecutionEvent.Credit(tick)) }
            }

        val fillJobs = ArrayDeque<Job>()

        fun launchFillWatcher(orderId: Int) {
            fillJobs.addLast(
                scope.launch {
                    val status = orderExecutionPort.awaitFill(orderId)
                    eventChannel.trySend(ExecutionEvent.Fill(status, orderId))
                },
            )
        }

        // Time-based ladder: fires every priceAdjustIntervalSeconds regardless of market data stream health.
        // This ensures the order chases toward the bid even when EUREX options data is not subscribed.
        val timerJob: Job =
            scope.launch {
                while (true) {
                    delay(config.priceAdjustIntervalSeconds * 1_000L)
                    eventChannel.trySend(ExecutionEvent.Timer)
                }
            }

        launchFillWatcher(currentOrderId)

        try {
            withTimeout(config.executionTimeoutMinutes * 60_000L) {
                for (event in eventChannel) {
                    when (event) {
                        is ExecutionEvent.Fill -> {
                            if (event.orderId != currentOrderId) {
                                if (event.status == OrderStatus.FILLED) {
                                    // A previously laddered/replaced order actually filled before we
                                    // moved on. Honor it instead of discarding — otherwise a real
                                    // position goes untracked (E4). Credit is recorded at the current
                                    // ladder step (conservative: the older order's credit was >= this).
                                    logger.warn {
                                        "[${request.underlyingSymbol}] Stale order ${event.orderId} reports FILLED " +
                                            "(current=$currentOrderId) — honoring it as the fill"
                                    }
                                    currentOrderId = event.orderId
                                    outcome = ExecutionOutcome.FILLED
                                    break
                                }
                                // Non-fill terminal state on a replaced order — safe to ignore.
                                logger.debug {
                                    "[${request.underlyingSymbol}] Ignoring stale ${event.status} " +
                                        "for orderId=${event.orderId} (current=$currentOrderId)"
                                }
                                continue
                            }
                            when (event.status) {
                                OrderStatus.FILLED -> {
                                    outcome = ExecutionOutcome.FILLED
                                    break
                                }
                                OrderStatus.CANCELLED -> {
                                    logger.info {
                                        "[${request.underlyingSymbol}] ORDER_REJECTED — " +
                                            "orderId=$currentOrderId rejected/cancelled by broker"
                                    }
                                    outcome = ExecutionOutcome.ORDER_REJECTED
                                    break
                                }
                                OrderStatus.PENDING -> continue
                            }
                        }

                        is ExecutionEvent.Underlying -> {
                            val entryPrice = request.underlyingPriceAtEntry.toDouble()
                            val drift = abs(event.price - entryPrice) / entryPrice
                            if (drift > config.driftProtectionPct) {
                                logger.info {
                                    "[${request.underlyingSymbol}] DRIFT_ABORTED — " +
                                        "drift=${"%.2f".format(drift * 100)}% > threshold=${config.driftProtectionPct * 100}%"
                                }
                                orderExecutionPort.cancelAndAwait(currentOrderId)
                                outcome = ExecutionOutcome.DRIFT_ABORTED
                                break
                            }
                        }

                        is ExecutionEvent.Credit -> {
                            ticksSinceAdjust++
                            if (ticksSinceAdjust >= config.ticksBeforePriceAdjust) {
                                ticksSinceAdjust = 0
                                val stepped = ladder(request, currentOrderId, currentCredit)
                                if (stepped == null) {
                                    outcome = ExecutionOutcome.FLOOR_REACHED
                                    break
                                }
                                currentCredit = stepped.first
                                currentOrderId = stepped.second
                                launchFillWatcher(currentOrderId)
                            }
                        }

                        is ExecutionEvent.Timer -> {
                            ticksSinceAdjust = 0
                            val stepped = ladder(request, currentOrderId, currentCredit)
                            if (stepped == null) {
                                outcome = ExecutionOutcome.FLOOR_REACHED
                                break
                            }
                            currentCredit = stepped.first
                            currentOrderId = stepped.second
                            launchFillWatcher(currentOrderId)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.info {
                "[${request.underlyingSymbol}] TIMED_OUT after ${config.executionTimeoutMinutes} min"
            }
            runCatching { orderExecutionPort.cancelAndAwait(currentOrderId) }
                .onFailure { ex -> logger.warn(ex) { "[${request.underlyingSymbol}] Cancel on timeout failed" } }
            outcome = ExecutionOutcome.TIMED_OUT
        } catch (e: CancellationException) {
            throw e
        } finally {
            underlyingJob.cancel()
            creditJob.cancel()
            timerJob.cancel()
            fillJobs.forEach { it.cancel() }
            eventChannel.close()
        }

        if (outcome == ExecutionOutcome.FILLED) {
            // Deduct entry fees (2 legs × feePerContract / contractMultiplier = fee per share)
            val feePerShare =
                config.feePerContract
                    .multiply(BigDecimal("2"))
                    .divide(config.contractMultiplier, 6, RoundingMode.HALF_UP)
            val netCredit = currentCredit.subtract(feePerShare).setScale(4, RoundingMode.HALF_UP)
            writer.markFilled(pendingSpread, currentOrderId, netCredit)
            logger.info {
                "[${request.underlyingSymbol}] FILLED — " +
                    "gross=\$$currentCredit fees=\$$feePerShare net=\$$netCredit orderId=$currentOrderId spread saved"
            }
            tradeLogger.info {
                "ENTRY  ${request.underlyingSymbol}  ${request.soldContract.strike}P/${request.boughtContract.strike}P" +
                    "  exp=${request.soldContract.expiry}  credit=\$$netCredit  iv_rank=${"%.1f".format(request.ivRankAtEntry)}%" +
                    "  underlying=${request.underlyingPriceAtEntry}  order=$currentOrderId"
            }
            return TradeExecutionResult(ExecutionOutcome.FILLED, netCredit, currentOrderId)
        }

        tradeLogger.info {
            "ABORTED ${request.underlyingSymbol}  ${request.soldContract.strike}P/${request.boughtContract.strike}P  reason=${outcome.name}"
        }
        val abortStatus = if (outcome == ExecutionOutcome.ORDER_REJECTED) SpreadStatus.CLOSED_REJECTED else SpreadStatus.CLOSED_TIMEOUT
        writer.markStatus(pendingSpread, abortStatus, outcome.name.lowercase())
        val cooldownExpiry = Instant.now(clock).plusSeconds(config.entryCooldownMinutes * 60)
        cooldownUntil[request.underlyingSymbol] = cooldownExpiry
        logger.info {
            "[${request.underlyingSymbol}] Entry cooldown set — won't retry until $cooldownExpiry (${config.entryCooldownMinutes} min)"
        }
        return TradeExecutionResult(outcome)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private suspend fun ladder(
        request: TradeExecutionRequest,
        currentOrderId: Int,
        currentCredit: BigDecimal,
    ): Pair<BigDecimal, Int>? {
        val tickSize = minTickFor(currentCredit)
        val newCredit = currentCredit.subtract(tickSize).setScale(2, RoundingMode.HALF_DOWN)
        if (newCredit < request.floorCredit) {
            logger.info { "[${request.underlyingSymbol}] FLOOR_REACHED — next=\$$newCredit < floor=\$${request.floorCredit}" }
            orderExecutionPort.cancelAndAwait(currentOrderId)
            return null
        }
        val newOrderId =
            orderExecutionPort.replaceComboWithNewPrice(
                existingOrderId = currentOrderId,
                soldContract = request.soldContract,
                boughtContract = request.boughtContract,
                newCredit = Money(newCredit),
                qty = request.quantity,
            )
        logger.info { "[${request.underlyingSymbol}] Laddered to \$$newCredit (orderId=$newOrderId)" }
        return Pair(newCredit, newOrderId)
    }

    private fun minTickFor(price: BigDecimal): BigDecimal = if (price < BigDecimal("3.00")) BigDecimal("0.01") else BigDecimal("0.05")

    private suspend fun calculateFreshCredit(request: TradeExecutionRequest): Triple<BigDecimal, QuoteFreshnessResult?, SpreadCreditTick?> {
        val startNanos = System.nanoTime()
        logger.info { "[${request.underlyingSymbol}] calculateFreshCredit: waiting 500ms for market data to settle" }
        delay(500) // Wait for market data to settle

        return try {
            val stream =
                marketTickPort.streamSpreadCredit(
                    request.soldContract,
                    request.boughtContract,
                )

            logger.info { "[${request.underlyingSymbol}] calculateFreshCredit: waiting up to 3s for first tick" }
            // streamSpreadCredit is a hot callbackFlow that never completes. Take the FIRST usable
            // tick and break — do NOT collect-with-timeout (that always hits the 3s timeout and
            // discards the computed credit). The timeout bounds the wait for that first tick only.
            val tick = withTimeout(3_000L) { stream.first() }

            logger.info {
                "[${request.underlyingSymbol}] calculateFreshCredit: received tick " +
                    "sold=${tick.soldBid}/${tick.soldAsk} bought=${tick.boughtBid}/${tick.boughtAsk} net=${tick.netCredit}"
            }

            // Use bid side for entry: short leg's bid minus long leg's ask (widest spread, safest)
            val bidCredit =
                (tick.soldBid - tick.boughtAsk)
                    .toBigDecimal()
                    .setScale(4, RoundingMode.HALF_UP)

            // Also check mid to measure how much the market has moved
            val soldMid = (tick.soldBid + tick.soldAsk) / 2.0
            val boughtMid = (tick.boughtBid + tick.boughtAsk) / 2.0
            val currentMid =
                (soldMid - boughtMid)
                    .toBigDecimal()
                    .setScale(4, RoundingMode.HALF_UP)

            val priceDrift = (currentMid - request.targetCredit).abs()

            logger.info {
                "[${request.underlyingSymbol}] calculateFreshCredit: analysis " +
                    "bidCredit=\$$bidCredit currentMid=\$$currentMid target=\$${request.targetCredit} " +
                    "drift=\$$priceDrift"
            }

            if (priceDrift > BigDecimal("0.10")) {
                // Market moved more than $0.10 — safety abort
                val percent = "%.1f".format(priceDrift / request.targetCredit.max(BigDecimal("0.01")) * BigDecimal("100"))
                logger.warn {
                    "[${request.underlyingSymbol}] Market moved too far: " +
                        "scanner=\$${request.targetCredit} current-mid=\$$currentMid " +
                        "drift=\$$priceDrift ($percent%)"
                }
                return Triple(
                    BigDecimal.ZERO,
                    QuoteFreshnessResult(
                        outcome = ExecutionOutcome.MARKET_MOVED_TOO_FAR,
                        message =
                            "[${request.underlyingSymbol}] Market moved " +
                                "\$$priceDrift from target; aborting to prevent bad order",
                    ),
                    null,
                )
            }

            // Use bid side if available, but don't go below floor.
            // Retain the fresh per-leg NBBO for leg-by-leg pricing (LegQuotes).
            val freshCredit = bidCredit.coerceAtLeast(request.floorCredit)
            logger.info {
                "[${request.underlyingSymbol}] Fresh quotes: mid=\$$currentMid bid=\$$bidCredit floor=\$${request.floorCredit} " +
                    "using=\$$freshCredit"
            }
            Triple(freshCredit, null, tick)
        } catch (e: TimeoutCancellationException) {
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            // DIAGNOSTIC: this is market-data starvation, NOT price drift — but it is currently
            // reported as MARKET_MOVED_TOO_FAR. Log the true cause + contract context so the no-tick
            // rate (and its likely cause: ambiguous no-conId contract or exhausted mkt-data lines)
            // is visible in the journal. See contractForMktData fallback + acquireMarketDataLine logs.
            logger.warn {
                "[${request.underlyingSymbol}] NO MARKET-DATA TICK in ${elapsedMs}ms (starvation, not price drift) — " +
                    "aborting entry. sold=${request.soldContract.strike}P/${request.soldContract.expiry} " +
                    "bought=${request.boughtContract.strike}P/${request.boughtContract.expiry}"
            }
            Triple(
                BigDecimal.ZERO,
                QuoteFreshnessResult(
                    outcome = ExecutionOutcome.NO_MARKET_DATA,
                    message = "[${request.underlyingSymbol}] No market data tick received in ${elapsedMs}ms (no-tick starvation)",
                ),
                null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) {
                "[${request.underlyingSymbol}] Error fetching fresh quotes; aborting entry"
            }
            Triple(
                BigDecimal.ZERO,
                QuoteFreshnessResult(
                    outcome = ExecutionOutcome.MARKET_MOVED_TOO_FAR,
                    message = "[${request.underlyingSymbol}] Quote fetch error: ${e.message}",
                ),
                null,
            )
        }
    }

    private data class QuoteFreshnessResult(
        val outcome: ExecutionOutcome,
        val message: String,
    )

    // -------------------------------------------------------------------------
    // Internal event types
    // -------------------------------------------------------------------------

    private sealed interface ExecutionEvent {
        data class Underlying(
            val price: Double,
        ) : ExecutionEvent

        data class Credit(
            val tick: SpreadCreditTick,
        ) : ExecutionEvent

        data class Fill(
            val status: OrderStatus,
            val orderId: Int,
        ) : ExecutionEvent

        data object Timer : ExecutionEvent
    }
}
