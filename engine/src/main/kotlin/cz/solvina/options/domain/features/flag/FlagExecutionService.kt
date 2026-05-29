package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.flag.EntryFill
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("FLAG_TRADES")

@Service
class FlagExecutionService(
    private val bracketOrderPort: BracketOrderPort,
    private val flagPort: FlagPort,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    data class ExecutionRequest(
        val symbol: Symbol,
        val entryPrice: BigDecimal,
        val stopLossPrice: BigDecimal,
        val flagpoleHeight: BigDecimal,
        val flagRetracement: BigDecimal,
        val resistanceAtEntry: BigDecimal,
        val patternStartedAt: Instant?,
        val tradingConfig: FlagTradingConfig,
    )

    /**
     * Calculates share size, submits bracket order, persists PENDING, then
     * asynchronously awaits fills and updates status accordingly.
     */
    suspend fun execute(request: ExecutionRequest) {
        val risk = request.stopLossPrice.let { stop ->
            request.entryPrice.subtract(stop).abs()
        }
        if (risk <= BigDecimal.ZERO) {
            logger.warn { "[${request.symbol}] Invalid risk $risk (entry=${request.entryPrice} stop=${request.stopLossPrice}) — skipping" }
            return
        }

        val shares = request.tradingConfig.riskPerTrade
            .divide(risk, 0, RoundingMode.FLOOR)
            .toInt()
            .coerceAtLeast(1)

        val profitTarget = request.entryPrice.add(
            request.entryPrice.subtract(request.stopLossPrice).multiply(BigDecimal.valueOf(2)),
        ).setScale(2, RoundingMode.HALF_UP)

        logger.info {
            "[${request.symbol}] Submitting bracket order: entry=${request.entryPrice} " +
                "stop=${request.stopLossPrice} pt=$profitTarget shares=$shares " +
                "risk=\$${risk.multiply(BigDecimal(shares)).setScale(2, RoundingMode.HALF_UP)}"
        }

        val ids = runCatching {
            bracketOrderPort.submitBracketOrder(
                symbol = request.symbol,
                shares = shares,
                entryPrice = request.entryPrice,
                stopLossPrice = request.stopLossPrice,
                profitTargetPrice = profitTarget,
            )
        }.getOrElse { e ->
            logger.error(e) { "[${request.symbol}] Bracket order submission failed: ${e.message}" }
            return
        }

        var position = flagPort.save(
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
                shares = shares,
                riskAmount = request.tradingConfig.riskPerTrade,
                flagpoleHeight = request.flagpoleHeight,
                flagRetracement = request.flagRetracement,
                resistanceAtEntry = request.resistanceAtEntry,
                patternStartedAt = request.patternStartedAt,
                openedAt = Instant.now(clock),
            ),
        )

        tradeLogger.info {
            "ENTRY_PENDING ${request.symbol} entry=${request.entryPrice} stop=${request.stopLossPrice} " +
                "pt=$profitTarget shares=$shares entryOrder=${ids.entryOrderId}"
        }

        // Await parent fill in background
        scope.launch {
            val entryFill = runCatching { bracketOrderPort.awaitParentFill(ids.entryOrderId) }
                .getOrElse { e ->
                    logger.warn(e) { "[${request.symbol}] Parent fill await failed: ${e.message}" }
                    EntryFill(OrderStatus.CANCELLED)
                }

            if (entryFill.status != OrderStatus.FILLED) {
                logger.info { "[${request.symbol}] Entry order not filled (status=${entryFill.status}) — marking cancelled" }
                flagPort.update(position.copy(status = FlagStatus.CLOSED_MANUAL, closeReason = "entry_not_filled", closedAt = Instant.now(clock)))
                return@launch
            }

            position = flagPort.update(position.copy(status = FlagStatus.OPEN, actualEntryPrice = entryFill.avgPrice))
            tradeLogger.info { "ENTRY_FILLED ${request.symbol} entryOrder=${ids.entryOrderId} shares=$shares entry=${request.entryPrice} actualFill=${entryFill.avgPrice}" }

            // Now watch both children — whichever fills first wins (OCA cancels the other)
            awaitChildOutcome(position, ids)
        }
    }

    private suspend fun awaitChildOutcome(
        position: FlagPosition,
        ids: BracketOrderIds,
    ) {
        // Wait for SL
        scope.launch {
            val status = runCatching { bracketOrderPort.awaitChildFill(ids.stopLossOrderId) }.getOrNull()
            if (status == OrderStatus.FILLED) {
                val pnl = position.stopLossPrice
                    .subtract(position.entryPrice)
                    .multiply(BigDecimal(position.shares))
                    .setScale(2, RoundingMode.HALF_UP)
                flagPort.update(
                    position.copy(
                        status = FlagStatus.CLOSED_STOP,
                        closedAt = Instant.now(clock),
                        closeReason = "stop_loss",
                        closePriceActual = position.stopLossPrice,
                        realizedPnl = pnl,
                    ),
                )
                tradeLogger.info { "CLOSED_STOP ${position.symbol} pnl=\$$pnl" }
            }
        }

        // Wait for PT
        scope.launch {
            val status = runCatching { bracketOrderPort.awaitChildFill(ids.profitTargetOrderId) }.getOrNull()
            if (status == OrderStatus.FILLED) {
                val pnl = position.profitTargetPrice
                    .subtract(position.entryPrice)
                    .multiply(BigDecimal(position.shares))
                    .setScale(2, RoundingMode.HALF_UP)
                flagPort.update(
                    position.copy(
                        status = FlagStatus.CLOSED_PROFIT,
                        closedAt = Instant.now(clock),
                        closeReason = "profit_target",
                        closePriceActual = position.profitTargetPrice,
                        realizedPnl = pnl,
                    ),
                )
                tradeLogger.info { "CLOSED_PROFIT ${position.symbol} pnl=\$$pnl" }
            }
        }
    }
}
