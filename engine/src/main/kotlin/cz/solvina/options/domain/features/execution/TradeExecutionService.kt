package cz.solvina.options.domain.features.execution

import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.execution.model.TradeExecutionResult
import cz.solvina.options.domain.features.market.MarketTickPort
import cz.solvina.options.domain.features.market.SpreadCreditTick
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.OrderExecutionPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.spread.SpreadPort
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
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
import kotlinx.coroutines.launch
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
    private val spreadPort: SpreadPort,
    private val validator: PreTradeValidator,
    private val config: ScannerConfig,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : TradeExecutionPort {
    private val inFlightSymbols = ConcurrentHashMap<Symbol, Unit>()
    private val cooldownUntil = ConcurrentHashMap<Symbol, Instant>()

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

        inFlightSymbols[request.underlyingSymbol] = Unit
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
        // Persist PENDING before submitting — survives engine restarts so fill can be recovered
        var pendingSpread = spreadPort.save(buildSpread(request, orderId = 0, credit = request.targetCredit, status = SpreadStatus.PENDING))

        // Check quote freshness before submission — market may have moved since scanner captured mid-prices
        val stalePriceResult = validateQuoteFreshness(request)
        if (stalePriceResult != null) {
            logger.info { "[${request.underlyingSymbol}] ${stalePriceResult.message}" }
            spreadPort.update(
                pendingSpread.copy(
                    status = SpreadStatus.CLOSED_TIMEOUT,
                    closeReason = stalePriceResult.outcome.name.lowercase(),
                ),
            )
            return TradeExecutionResult(stalePriceResult.outcome)
        }

        var currentOrderId =
            runCatching {
                orderExecutionPort.submitComboLimitOrder(
                    soldContract = request.soldContract,
                    boughtContract = request.boughtContract,
                    netCredit = Money(request.targetCredit),
                    qty = request.quantity,
                )
            }.getOrElse { e ->
                logger.error(e) { "[${request.underlyingSymbol}] Failed to submit order" }
                spreadPort.update(pendingSpread.copy(status = SpreadStatus.CLOSED_REJECTED, closeReason = "order_rejected: ${e.message}"))
                return TradeExecutionResult(ExecutionOutcome.ORDER_REJECTED)
            }
        // Stamp the real orderId now that we have it
        pendingSpread =
            spreadPort.update(
                pendingSpread.copy(
                    soldLeg = pendingSpread.soldLeg.copy(orderId = currentOrderId),
                    boughtLeg = pendingSpread.boughtLeg.copy(orderId = currentOrderId),
                ),
            )
        var currentCredit = request.targetCredit
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
                                // Stale fill from a previously cancelled-and-replaced order
                                logger.debug {
                                    "[${request.underlyingSymbol}] Ignoring stale fill " +
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
            // Deduct entry fees (2 legs × feePerContract / 100 = fee per share)
            val feePerShare = config.feePerContract.multiply(BigDecimal("2")).divide(BigDecimal("100"))
            val netCredit = currentCredit.subtract(feePerShare).setScale(4, RoundingMode.HALF_UP)
            spreadPort.update(
                pendingSpread.copy(
                    soldLeg = pendingSpread.soldLeg.copy(orderId = currentOrderId),
                    boughtLeg = pendingSpread.boughtLeg.copy(orderId = currentOrderId),
                    creditPerShare = netCredit,
                    status = SpreadStatus.OPEN,
                ),
            )
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
        spreadPort.update(pendingSpread.copy(status = abortStatus, closeReason = outcome.name.lowercase()))
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

    private fun buildSpread(
        request: TradeExecutionRequest,
        orderId: Int,
        credit: BigDecimal,
        status: SpreadStatus = SpreadStatus.OPEN,
    ): BullPutSpread {
        val soldPremium = credit.add(request.boughtMid).setScale(4, RoundingMode.HALF_UP)
        return BullPutSpread(
            id = null,
            symbol = request.underlyingSymbol,
            soldLeg = SpreadLeg(contract = request.soldContract, action = LegAction.SELL, premium = Money(soldPremium), orderId = orderId),
            boughtLeg =
                SpreadLeg(
                    contract = request.boughtContract,
                    action = LegAction.BUY,
                    premium = Money(request.boughtMid),
                    orderId = orderId,
                ),
            creditPerShare = credit,
            maxRiskPerShare = request.maxRiskPerShare,
            quantity = request.quantity,
            status = status,
            ivRankAtEntry = request.ivRankAtEntry,
            underlyingPriceAtEntry = request.underlyingPriceAtEntry,
            openedAt = Instant.now(clock),
        )
    }

    private suspend fun validateQuoteFreshness(request: TradeExecutionRequest): QuoteFreshnessResult? {
        // Wait a moment to allow market data to flow through
        delay(500)

        val currentTick =
            runCatching {
                val stream =
                    marketTickPort.streamSpreadCredit(
                        request.soldContract,
                        request.boughtContract,
                    )
                withTimeout(3_000L) {
                    stream.collect { tick ->
                        val soldMid = (tick.soldBid + tick.soldAsk) / 2.0
                        val boughtMid = (tick.boughtBid + tick.boughtAsk) / 2.0
                        val currentMid =
                            (soldMid - boughtMid)
                                .toBigDecimal()
                                .setScale(4, RoundingMode.HALF_UP)

                        val priceDrift = (currentMid - request.targetCredit).abs()
                        val driftPercent =
                            priceDrift / request.targetCredit.max(BigDecimal("0.01"))

                        if (priceDrift > BigDecimal("0.05")) {
                            val percent = "%.1f".format(driftPercent * BigDecimal("100"))
                            logger.warn {
                                "[${request.underlyingSymbol}] Quote staleness detected: " +
                                    "scanner=$${request.targetCredit} current=$$currentMid " +
                                    "drift=$$priceDrift ($percent%)"
                            }
                            throw StaleQuoteException(currentMid, request.targetCredit, priceDrift)
                        } else {
                            throw ValidQuoteException()
                        }
                    }
                }
            }.getOrElse { e ->
                when (e) {
                    is ValidQuoteException -> return null
                    is StaleQuoteException -> {
                        return QuoteFreshnessResult(
                            outcome = ExecutionOutcome.MARKET_MOVED_TOO_FAR,
                            message =
                                "[${request.underlyingSymbol}] Market moved " +
                                    "$${e.drift} from target; aborting to prevent bad order",
                        )
                    }
                    is TimeoutCancellationException -> {
                        logger.warn {
                            "[${request.underlyingSymbol}] Market data timeout — no tick in 3s, aborting entry"
                        }
                        return QuoteFreshnessResult(
                            outcome = ExecutionOutcome.MARKET_MOVED_TOO_FAR,
                            message = "[${request.underlyingSymbol}] Quote validation timeout: no market data tick received in 3s",
                        )
                    }
                    else -> {
                        logger.warn(e) {
                            "[${request.underlyingSymbol}] Error checking quote freshness; aborting entry"
                        }
                        return QuoteFreshnessResult(
                            outcome = ExecutionOutcome.MARKET_MOVED_TOO_FAR,
                            message = "[${request.underlyingSymbol}] Quote validation error: ${e.message}",
                        )
                    }
                }
            }
        return null
    }

    private data class QuoteFreshnessResult(
        val outcome: ExecutionOutcome,
        val message: String,
    )

    private class StaleQuoteException(
        val current: BigDecimal,
        val target: BigDecimal,
        val drift: BigDecimal,
    ) : Exception()

    private class ValidQuoteException : Exception()

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
