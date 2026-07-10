package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.account.EffectiveAccountService
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.flag.model.isTerminal
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("FLAG_TRADES")

@Service
class FlagExecutionService(
    private val bracketOrderPort: BracketOrderPort,
    private val flagPort: FlagPort,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val effectiveAccount: EffectiveAccountService,
    // Hard cap on a single flag position as a fraction of account capital. Risk-based sizing
    // (riskPerTrade ÷ stop-distance) alone ignores NOTIONAL: a tight stop yields a huge share count
    // whose dollar value can dwarf the account and sweep the book (e.g. GOOGL 2000sh ≈ $686k off a
    // $100 risk). This caps shares at maxPositionPctOfCapital × capital ÷ entryPrice. Account-relative
    // so it self-scales to the paper balance and a small live account alike.
    @param:Value("\${flag.max-position-pct-of-capital:0.25}")
    private val maxPositionPctOfCapital: BigDecimal = BigDecimal("0.25"),
) {
    data class ExecutionRequest(
        val symbol: Symbol,
        val entryPrice: BigDecimal,
        val stopLossPrice: BigDecimal,
        val flagpoleHeight: BigDecimal,
        val flagRetracement: BigDecimal,
        val resistanceAtEntry: BigDecimal,
        val patternStartedAt: Instant?,
        val signalTime: Instant,
        val tradingConfig: FlagTradingConfig,
        val flagBarCount: Int? = null,
        val flagpoleBarCount: Int? = null,
        val flagpoleAvgVolume: Long? = null,
        val flagAvgVolume: Long? = null,
        val channelSlope: BigDecimal? = null,
        val marketSession: String? = null,
        val minutesToClose: Int? = null,
        val atrAtEntry: BigDecimal? = null,
        val volumeMaAtEntry: Long? = null,
        val flagpoleVolumeRatio: BigDecimal? = null,
        val vwapAtEntry: BigDecimal? = null,
        val dayOpenPrice: BigDecimal? = null,
        val breakoutType: String? = null,
        val stopDistancePct: BigDecimal? = null,
    )

    /**
     * Calculates share size, submits bracket order, persists PENDING, then
     * asynchronously awaits fills and updates status accordingly.
     */
    suspend fun execute(request: ExecutionRequest) {
        val risk =
            request.stopLossPrice.let { stop ->
                request.entryPrice.subtract(stop).abs()
            }
        if (risk <= BigDecimal.ZERO) {
            logger.warn { "[${request.symbol}] Invalid risk $risk (entry=${request.entryPrice} stop=${request.stopLossPrice}) — skipping" }
            return
        }

        val riskBasedShares =
            request.tradingConfig.riskPerTrade
                .divide(risk, 0, RoundingMode.FLOOR)
                .toInt()
                .coerceAtLeast(1)

        // Notional cap: never let a single position exceed maxPositionPctOfCapital of account capital,
        // regardless of how tight the stop makes the risk-based share count. Falls back to risk-based
        // sizing only when capital is unknown (account feed not yet populated).
        val capital = effectiveAccount.detail()?.totalCapital?.amount
        val shares =
            if (capital != null && capital > BigDecimal.ZERO) {
                val maxNotional = capital.multiply(maxPositionPctOfCapital)
                val notionalCappedShares =
                    maxNotional.divide(request.entryPrice, 0, RoundingMode.FLOOR).toInt().coerceAtLeast(1)
                if (notionalCappedShares < riskBasedShares) {
                    logger.warn {
                        "[${request.symbol}] Notional cap applied: $riskBasedShares → $notionalCappedShares shares " +
                            "(max ${maxPositionPctOfCapital.multiply(BigDecimal(100)).toInt()}% of capital \$$capital = " +
                            "\$$maxNotional at entry \$${request.entryPrice})"
                    }
                    notionalCappedShares
                } else {
                    riskBasedShares
                }
            } else {
                logger.warn { "[${request.symbol}] Account capital unknown — sizing by risk only (no notional cap this entry)" }
                riskBasedShares
            }

        // Best config: trailing stop 2R behind the peak (no fixed target), held overnight. The
        // trail distance is 2 × initial risk; profitTarget is kept only as a nominal display
        // reference (entry + 2R) — the live protective exit is the trailing stop.
        val trailAmount =
            request.entryPrice
                .subtract(request.stopLossPrice)
                .multiply(BigDecimal.valueOf(2))
                .setScale(2, RoundingMode.HALF_UP)
        val profitTarget = request.entryPrice.add(trailAmount).setScale(2, RoundingMode.HALF_UP)

        logger.info {
            "[${request.symbol}] Submitting entry + trailing stop: entry=${request.entryPrice} " +
                "stop=${request.stopLossPrice} trail=\$$trailAmount shares=$shares " +
                "risk=\$${risk.multiply(BigDecimal(shares)).setScale(2, RoundingMode.HALF_UP)}"
        }

        val ids =
            runCatching {
                bracketOrderPort.submitBracketOrder(
                    symbol = request.symbol,
                    shares = shares,
                    entryPrice = request.entryPrice,
                    stopLossPrice = request.stopLossPrice,
                    trailAmount = trailAmount,
                )
            }.getOrElse { e ->
                logger.error(e) { "[${request.symbol}] Bracket order submission failed: ${e.message}" }
                return
            }

        val position =
            flagPort.save(
                FlagPosition(
                    id = null,
                    symbol = request.symbol,
                    status = FlagStatus.PENDING,
                    entryOrderId = ids.entryOrderId,
                    stopLossOrderId = ids.stopLossOrderId,
                    profitTargetOrderId = ids.profitTargetOrderId,
                    entryPrice = request.entryPrice,
                    stopLossPrice = request.stopLossPrice,
                    profitTargetPrice = profitTarget,
                    trailAmount = trailAmount,
                    shares = shares,
                    riskAmount = request.tradingConfig.riskPerTrade,
                    flagpoleHeight = request.flagpoleHeight,
                    flagRetracement = request.flagRetracement,
                    resistanceAtEntry = request.resistanceAtEntry,
                    patternStartedAt = request.patternStartedAt,
                    openedAt = request.signalTime,
                    flagBarCount = request.flagBarCount,
                    flagpoleBarCount = request.flagpoleBarCount,
                    flagpoleAvgVolume = request.flagpoleAvgVolume,
                    flagAvgVolume = request.flagAvgVolume,
                    channelSlope = request.channelSlope,
                    marketSession = request.marketSession,
                    minutesToClose = request.minutesToClose,
                    atrAtEntry = request.atrAtEntry,
                    volumeMaAtEntry = request.volumeMaAtEntry,
                    flagpoleVolumeRatio = request.flagpoleVolumeRatio,
                    vwapAtEntry = request.vwapAtEntry,
                    dayOpenPrice = request.dayOpenPrice,
                    breakoutType = request.breakoutType,
                    stopDistancePct = request.stopDistancePct,
                ),
            )

        tradeLogger.info {
            "ENTRY_PENDING ${request.symbol} entry=${request.entryPrice} stop=${request.stopLossPrice} " +
                "pt=$profitTarget shares=$shares entryOrder=${ids.entryOrderId}"
        }

        // Await parent fill in background
        launchEntryWatch(position)
    }

    /**
     * Watches the parent entry order in the background; on fill promotes the position to OPEN and
     * starts the exit watches. [rewatch] means the orders were placed by a previous engine run
     * (startup/periodic recovery): the fill watch is re-armed instead of expecting the deferred
     * registered at order placement.
     */
    fun launchEntryWatch(
        position: FlagPosition,
        rewatch: Boolean = false,
    ) {
        val entryOrderId = position.entryOrderId
        scope.launch {
            val entryFill =
                runCatching {
                    if (rewatch) bracketOrderPort.rewatchParentFill(entryOrderId) else bracketOrderPort.awaitParentFill(entryOrderId)
                }.getOrElse { e ->
                    logger.warn(e) { "[${position.symbol}] Parent fill await failed: ${e.message}" }
                    OrderFill(OrderStatus.CANCELLED)
                }

            if (entryFill.status != OrderStatus.FILLED) {
                logger.info { "[${position.symbol}] Entry order not filled (status=${entryFill.status}) — marking cancelled" }
                flagPort.update(
                    position.copy(status = FlagStatus.ENTRY_TIMEOUT, closeReason = "entry_not_filled", closedAt = Instant.now(clock)),
                )
                return@launch
            }

            val entrySlippage = entryFill.avgPrice?.subtract(position.entryPrice)
            val open =
                flagPort.update(
                    position.copy(status = FlagStatus.OPEN, actualEntryPrice = entryFill.avgPrice, entrySlippage = entrySlippage),
                )
            tradeLogger.info {
                "ENTRY_FILLED ${position.symbol} entryOrder=$entryOrderId shares=${position.shares} " +
                    "entry=${position.entryPrice} actualFill=${entryFill.avgPrice} slippage=$entrySlippage"
            }

            // Entry is live — arm the protective-exit watch
            launchExitWatch(open, rewatch)
        }
    }

    /**
     * Watches the protective child orders of an OPEN position and books the close when one fills.
     * Public so recovery can re-attach watches to positions restored from persistence ([rewatch]).
     *
     * The protective exit is a single TRAIL order (its id stored as both stopLossOrderId and
     * profitTargetOrderId); legacy rows may still carry two distinct child ids. Exactly one watcher
     * is armed per distinct id — two watchers on the same id raced each other, and whichever won
     * booked its own theoretical price (stop or target) instead of the actual fill.
     */
    fun launchExitWatch(
        position: FlagPosition,
        rewatch: Boolean = false,
    ) {
        for (orderId in setOf(position.stopLossOrderId, position.profitTargetOrderId)) {
            scope.launch {
                val exit = runCatching { childFill(orderId, rewatch) }.getOrNull() ?: return@launch
                if (exit.status == OrderStatus.FILLED) bookExitFill(position, exit.avgPrice)
            }
        }
    }

    /**
     * Books the close of [position] after its protective order filled. A TRAIL SELL exits winners
     * and losers alike, so profit-vs-stop is decided by the realized P&L of the actual fill — not
     * by which order id filled. When the broker reported no fill price, the ratcheted trigger
     * (highest seen − trail) is the best estimate and the close reason says so.
     */
    private suspend fun bookExitFill(
        position: FlagPosition,
        fillPrice: BigDecimal?,
    ) {
        val closedAt = Instant.now(clock)
        val latest = position.id?.let { flagPort.findById(it) } ?: position
        if (latest.status.isTerminal) {
            logger.debug { "[${position.symbol}] Exit fill ignored — position already ${latest.status}" }
            return
        }
        val closePrice = fillPrice ?: estimatedTrailExitPrice(latest)
        val effectiveEntry = latest.actualEntryPrice ?: latest.entryPrice
        val pnl =
            closePrice
                .subtract(effectiveEntry)
                .multiply(BigDecimal(latest.shares))
                .setScale(2, RoundingMode.HALF_UP)
        val profitable = pnl.signum() >= 0
        val status = if (profitable) FlagStatus.CLOSED_PROFIT else FlagStatus.CLOSED_STOP
        val reason = (if (profitable) "trail_exit_profit" else "trail_exit_loss") + if (fillPrice == null) "_estimated" else ""
        flagPort.update(
            withCloseMetrics(
                latest.copy(
                    status = status,
                    closedAt = closedAt,
                    closeReason = reason,
                    closePriceActual = closePrice,
                    realizedPnl = pnl,
                ),
                pnl,
                closedAt,
            ),
        )
        tradeLogger.info { "${status.name} ${position.symbol} fill=$closePrice reason=$reason pnl=\$$pnl" }
    }

    /**
     * Where the trailing stop most plausibly filled when no fill price was reported: the ratcheted
     * trigger max(initial stop, highest seen − trail). Falls back to the initial stop for pre-v26
     * rows without a persisted trail.
     */
    private fun estimatedTrailExitPrice(position: FlagPosition): BigDecimal {
        val trail = position.trailAmount ?: return position.stopLossPrice
        val ratcheted = position.highestPriceSeen?.subtract(trail)
        return if (ratcheted != null && ratcheted > position.stopLossPrice) ratcheted else position.stopLossPrice
    }

    private suspend fun childFill(
        orderId: Int,
        rewatch: Boolean,
    ): OrderFill = if (rewatch) bracketOrderPort.rewatchChildFill(orderId) else bracketOrderPort.awaitChildFill(orderId)

    private fun withCloseMetrics(
        position: FlagPosition,
        pnl: BigDecimal,
        closedAt: Instant,
    ): FlagPosition {
        val shares = BigDecimal(position.shares)
        val effectiveEntry = position.actualEntryPrice ?: position.entryPrice
        val mfe =
            position.highestPriceSeen
                ?.subtract(effectiveEntry)
                ?.multiply(shares)
                ?.setScale(2, RoundingMode.HALF_UP)
        val mae =
            position.lowestPriceSeen
                ?.let { effectiveEntry.subtract(it) }
                ?.multiply(shares)
                ?.setScale(2, RoundingMode.HALF_UP)
        val rMultiple =
            if (position.riskAmount > BigDecimal.ZERO) {
                pnl.divide(position.riskAmount, 2, RoundingMode.HALF_UP)
            } else {
                null
            }
        val mfeR =
            if (mfe != null && position.riskAmount > BigDecimal.ZERO) {
                mfe.divide(position.riskAmount, 2, RoundingMode.HALF_UP)
            } else {
                null
            }
        val maeR =
            if (mae != null && position.riskAmount > BigDecimal.ZERO) {
                mae.divide(position.riskAmount, 2, RoundingMode.HALF_UP)
            } else {
                null
            }
        return position.copy(
            maxFavorableExcursion = mfe,
            maxAdverseExcursion = mae,
            rMultiple = rMultiple,
            mfeR = mfeR,
            maeR = maeR,
            timeInTradeSeconds = ChronoUnit.SECONDS.between(position.openedAt, closedAt).toInt(),
        )
    }
}
