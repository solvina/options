package cz.solvina.options.domain.features.execution

import cz.solvina.options.domain.features.account.AccountPort
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

@Service
class TradeExecutionService(
    private val marketTickPort: MarketTickPort,
    private val orderExecutionPort: OrderExecutionPort,
    private val spreadPort: SpreadPort,
    private val accountPort: AccountPort,
    private val config: ScannerConfig,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val inFlightSymbols = ConcurrentHashMap<Symbol, Unit>()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    suspend fun execute(request: TradeExecutionRequest): TradeExecutionResult {
        // Pre-trade checks
        val preCheck = preTradeCheck(request)
        if (preCheck != null) return TradeExecutionResult(preCheck)

        inFlightSymbols[request.underlyingSymbol] = Unit
        return try {
            executeInternal(request)
        } finally {
            inFlightSymbols.remove(request.underlyingSymbol)
        }
    }

    fun isInFlight(symbol: Symbol): Boolean = inFlightSymbols.containsKey(symbol)

    // -------------------------------------------------------------------------
    // Pre-trade checks
    // -------------------------------------------------------------------------

    private suspend fun preTradeCheck(request: TradeExecutionRequest): ExecutionOutcome? {
        // Exposure: open spreads + in-flight
        val openSymbols =
            spreadPort
                .findOpen()
                .map { it.symbol }
                .toSet()
        if (request.underlyingSymbol in openSymbols || inFlightSymbols.containsKey(request.underlyingSymbol)) {
            logger.info { "[${request.underlyingSymbol}] EXPOSURE_REJECTED — open or in-flight position exists" }
            return ExecutionOutcome.EXPOSURE_REJECTED
        }

        // Capital: available funds vs max risk per contract
        val availableFunds =
            accountPort.accountDetail.value
                ?.availableFunds
                ?.amount
        val maxRiskPerContract = request.maxRiskPerShare.multiply(BigDecimal("100"))
        if (availableFunds == null || availableFunds < maxRiskPerContract) {
            logger.info {
                "[${request.underlyingSymbol}] CAPITAL_REJECTED — available=\$$availableFunds " +
                    "required=\$$maxRiskPerContract"
            }
            return ExecutionOutcome.CAPITAL_REJECTED
        }

        // Liquidity: leg bid-ask spreads
        if (isLiquidityTooWide(request.soldBid, request.soldAsk, "sold")) {
            logger.info { "[${request.underlyingSymbol}] LIQUIDITY_REJECTED — sold leg spread too wide" }
            return ExecutionOutcome.LIQUIDITY_REJECTED
        }
        if (isLiquidityTooWide(request.boughtBid, request.boughtAsk, "bought")) {
            logger.info { "[${request.underlyingSymbol}] LIQUIDITY_REJECTED — bought leg spread too wide" }
            return ExecutionOutcome.LIQUIDITY_REJECTED
        }

        return null
    }

    private fun isLiquidityTooWide(
        bid: BigDecimal,
        ask: BigDecimal,
        leg: String,
    ): Boolean {
        val mid = bid.add(ask).divide(BigDecimal("2"), 4, RoundingMode.HALF_UP)
        if (mid <= BigDecimal.ZERO) {
            logger.debug { "Leg $leg mid is zero, skipping liquidity check" }
            return false
        }
        val spread = ask.subtract(bid)
        val spreadPct = spread.divide(mid, 4, RoundingMode.HALF_UP).toDouble()
        return spreadPct > config.maxLegBidAskSpreadPct
    }

    // -------------------------------------------------------------------------
    // Execution loop
    // -------------------------------------------------------------------------

    private suspend fun executeInternal(request: TradeExecutionRequest): TradeExecutionResult {
        var currentOrderId =
            orderExecutionPort.submitComboLimitOrder(
                soldContract = request.soldContract,
                boughtContract = request.boughtContract,
                netCredit = Money(request.targetCredit),
                qty = request.quantity,
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
                            if (event.status == OrderStatus.FILLED) {
                                outcome = ExecutionOutcome.FILLED
                                break
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
                                val tickSize = minTickFor(currentCredit)
                                val newCredit =
                                    currentCredit
                                        .subtract(tickSize)
                                        .setScale(2, RoundingMode.HALF_DOWN)
                                if (newCredit < request.floorCredit) {
                                    logger.info {
                                        "[${request.underlyingSymbol}] FLOOR_REACHED — " +
                                            "next=\$$newCredit < floor=\$${ request.floorCredit}"
                                    }
                                    orderExecutionPort.cancelAndAwait(currentOrderId)
                                    outcome = ExecutionOutcome.FLOOR_REACHED
                                    break
                                }
                                val prevOrderId = currentOrderId
                                currentOrderId =
                                    orderExecutionPort.replaceComboWithNewPrice(
                                        existingOrderId = prevOrderId,
                                        soldContract = request.soldContract,
                                        boughtContract = request.boughtContract,
                                        newCredit = Money(newCredit),
                                        qty = request.quantity,
                                    )
                                currentCredit = newCredit
                                ticksSinceAdjust = 0
                                launchFillWatcher(currentOrderId)
                                logger.info {
                                    "[${request.underlyingSymbol}] Laddered to \$$currentCredit " +
                                        "(orderId=$currentOrderId)"
                                }
                            }
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
            fillJobs.forEach { it.cancel() }
            eventChannel.close()
        }

        if (outcome == ExecutionOutcome.FILLED) {
            val spread = buildSpread(request, currentOrderId, currentCredit)
            spreadPort.save(spread)
            logger.info {
                "[${request.underlyingSymbol}] FILLED — " +
                    "credit=\$$currentCredit orderId=$currentOrderId spread saved"
            }
            return TradeExecutionResult(ExecutionOutcome.FILLED, currentCredit, currentOrderId)
        }

        return TradeExecutionResult(outcome)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun minTickFor(price: BigDecimal): BigDecimal = if (price < BigDecimal("3.00")) BigDecimal("0.01") else BigDecimal("0.05")

    private fun buildSpread(
        request: TradeExecutionRequest,
        comboOrderId: Int,
        creditAchieved: BigDecimal,
    ): BullPutSpread {
        val soldPremium =
            creditAchieved
                .add(request.boughtMid)
                .setScale(4, RoundingMode.HALF_UP)
        return BullPutSpread(
            id = null,
            symbol = request.underlyingSymbol,
            soldLeg =
                SpreadLeg(
                    contract = request.soldContract,
                    action = LegAction.SELL,
                    premium = Money(soldPremium),
                    orderId = comboOrderId,
                ),
            boughtLeg =
                SpreadLeg(
                    contract = request.boughtContract,
                    action = LegAction.BUY,
                    premium = Money(request.boughtMid),
                    orderId = comboOrderId,
                ),
            creditPerShare = creditAchieved,
            maxRiskPerShare = request.maxRiskPerShare,
            quantity = request.quantity,
            status = SpreadStatus.OPEN,
            ivRankAtEntry = request.ivRankAtEntry,
            underlyingPriceAtEntry = request.underlyingPriceAtEntry,
            openedAt = Instant.now(clock),
        )
    }

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
    }
}
