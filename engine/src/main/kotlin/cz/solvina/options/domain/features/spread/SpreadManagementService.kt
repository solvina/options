package cz.solvina.options.domain.features.spread

import cz.solvina.options.domain.features.execution.TradeExecutionPort
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.OrderPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.Money
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("TRADES")

@Service
class SpreadManagementService(
    private val spreadPort: SpreadPort,
    private val marketDataPort: MarketDataPort,
    private val orderPort: OrderPort,
    private val universePort: UniversePort,
    private val volatilityPort: VolatilityPort,
    private val executionPort: TradeExecutionPort,
    private val config: ScannerConfig,
    private val clock: Clock,
    private val positionsPort: cz.solvina.options.domain.features.account.PositionsPort? = null,
) {
    sealed interface ManualCloseResult {
        data class Closed(
            val spread: BullPutSpread,
        ) : ManualCloseResult

        data object NotFound : ManualCloseResult

        data class AlreadyClosed(
            val spread: BullPutSpread,
        ) : ManualCloseResult
    }

    suspend fun softClose(id: UUID): ManualCloseResult = manualClose(id, useMarket = false)

    suspend fun forceClose(id: UUID): ManualCloseResult = manualClose(id, useMarket = true)

    private val forceable = setOf(SpreadStatus.OPEN, SpreadStatus.CLOSING, SpreadStatus.CLOSED_STOP)

    private suspend fun manualClose(
        id: UUID,
        useMarket: Boolean,
    ): ManualCloseResult {
        val spread = spreadPort.findById(id) ?: return ManualCloseResult.NotFound
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
                spreadPort.findById(id) ?: spread
            }
        return ManualCloseResult.Closed(closed)
    }

    private suspend fun forceCloseSpread(
        spread: BullPutSpread,
        estimatedSpreadValue: BigDecimal,
        closeStatus: SpreadStatus = SpreadStatus.CLOSED_MANUAL,
        closeReason: String = "MANUAL_FORCE",
        exitContext: Pair<BigDecimal?, BigDecimal?> = Pair(null, null),
    ): BullPutSpread {
        logger.info { "[${spread.symbol}] Force-closing at market (reason=$closeReason)" }

        // Issue #7: Track order IDs for cancellation if verification fails
        var soldOrderId: Int? = null
        var boughtOrderId: Int? = null

        // Fire both legs; track order IDs for potential cancellation
        runCatching { orderPort.placeMarketOrder(spread.soldLeg.contract, LegAction.BUY, spread.quantity) }
            .onSuccess { legOrder -> soldOrderId = legOrder.orderId }
            .onFailure { e -> logger.warn { "[${spread.symbol}] Sold-leg market order timed out (order still submitted): ${e.message}" } }

        runCatching { orderPort.placeMarketOrder(spread.boughtLeg.contract, LegAction.SELL, spread.quantity) }
            .onSuccess { legOrder -> boughtOrderId = legOrder.orderId }
            .onFailure { e -> logger.warn { "[${spread.symbol}] Bought-leg market order timed out (order still submitted): ${e.message}" } }

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
                    }
                    .onFailure { e ->
                        logger.warn {
                            "[${spread.symbol}] Failed to cancel sold-leg order $soldOrderId: ${e.message}"
                        }
                    }
            }

            if (boughtOrderId != null && boughtOrderId!! > 0) {
                runCatching { orderPort.cancelOrder(boughtOrderId!!) }
                    .onSuccess {
                        logger.info { "[${spread.symbol}] Cancelled bought-leg order $boughtOrderId due to verification timeout" }
                    }
                    .onFailure { e ->
                        logger.warn {
                            "[${spread.symbol}] Failed to cancel bought-leg order $boughtOrderId: ${e.message}"
                        }
                    }
            }

            // Keep spread in CLOSING state; next retryClose will try again
            return spread
        }

        val updated =
            spread.copy(
                status = closeStatus,
                closedAt = Instant.now(clock),
                closeReason = closeReason,
                closePricePerShare = estimatedSpreadValue,
                underlyingPriceAtExit = exitContext.first,
                ivRankAtExit = exitContext.second,
            )
        spreadPort.update(updated)
        logger.info { "[${spread.symbol}] Force-closed. status=$closeStatus value=\$$estimatedSpreadValue" }
        return updated
    }

    /**
     * Verify that both legs of a spread are actually closed in IBKR.
     * Polls position feed up to 10 times with 500ms delays (5s total timeout).
     * Returns true if both legs are verified closed, false otherwise.
     */
    private suspend fun verifyPositionClosed(spread: BullPutSpread): Boolean {
        val port = positionsPort
        if (port == null) {
            logger.debug { "[${spread.symbol}] Position verification skipped (PositionsPort not available)" }
            return true // Assume closed if we can't verify
        }

        repeat(10) { attempt ->
            runCatching {
                val positions = port.getPositions()
                val soldLegClosed =
                    !positions.any { pos ->
                        pos.symbol == spread.symbol.value &&
                            pos.secType == "OPT" &&
                            pos.expiry == spread.soldLeg.contract.expiry &&
                            pos.strike == spread.soldLeg.contract.strike &&
                            pos.optionRight?.equals("Put", ignoreCase = true) == true &&
                            pos.quantity.compareTo(java.math.BigDecimal.ZERO) != 0
                    }
                val boughtLegClosed =
                    !positions.any { pos ->
                        pos.symbol == spread.symbol.value &&
                            pos.secType == "OPT" &&
                            pos.expiry == spread.boughtLeg.contract.expiry &&
                            pos.strike == spread.boughtLeg.contract.strike &&
                            pos.optionRight?.equals("Put", ignoreCase = true) == true &&
                            pos.quantity.compareTo(java.math.BigDecimal.ZERO) != 0
                    }

                if (soldLegClosed && boughtLegClosed) {
                    logger.info { "[${spread.symbol}] Position verification SUCCESS (attempt $attempt)" }
                    return true
                } else {
                    if (attempt < 9) {
                        logger.debug { "[${spread.symbol}] Position not yet closed (attempt $attempt), retrying..." }
                        delay(500)
                    }
                }
            }.onFailure { e ->
                logger.warn(e) { "[${spread.symbol}] Position verification error on attempt $attempt" }
            }
        }

        logger.warn { "[${spread.symbol}] Position verification TIMEOUT after 10 attempts (5s)" }
        return false
    }

    suspend fun checkExits() {
        val openSpreads = spreadPort.findOpen()
        val closingSpreads = spreadPort.findByStatus(SpreadStatus.CLOSING)

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

        if (tradableOpen.isNotEmpty()) {
            logger.info { "Checking exits for ${tradableOpen.size} open spread(s)" }
            for (spread in tradableOpen) {
                runCatching { checkSpreadExit(spread) }
                    .onFailure { e -> logger.error(e) { "[${spread.symbol}] Error checking spread exit: ${e.message}" } }
            }
        }

        if (tradableClosing.isNotEmpty()) {
            logger.info { "Retrying close for ${tradableClosing.size} stuck CLOSING spread(s)" }
            for (spread in tradableClosing) {
                runCatching { retryClose(spread) }
                    .onFailure { e -> logger.error(e) { "[${spread.symbol}] Error retrying close: ${e.message}" } }
            }
        }
    }

    private suspend fun retryClose(spread: BullPutSpread) {
        val soldMid = marketDataPort.getOptionMid(spread.soldLeg.contract)
        val boughtMid = marketDataPort.getOptionMid(spread.boughtLeg.contract)
        val currentSpreadValue = soldMid.amount.subtract(boughtMid.amount)
        val exitContext = captureExitContext(spread)

        // Re-evaluate against current market price — a stop-loss that sat in CLOSING while the
        // market recovered should close as PROFIT, not STOP.
        val inst = universePort.get(spread.symbol)
        val takeProfitPercent = inst?.takeProfitPercent ?: config.takeProfitPercent
        val stopLossPercent = inst?.stopLossPercent ?: config.stopLossPercent
        val tpThreshold = spread.creditPerShare.multiply(BigDecimal.ONE.subtract(BigDecimal(takeProfitPercent)))
        val slThreshold = spread.creditPerShare.add(spread.creditPerShare.multiply(BigDecimal(stopLossPercent)))

        val actualStatus =
            when {
                currentSpreadValue <= tpThreshold -> SpreadStatus.CLOSED_PROFIT
                currentSpreadValue >= slThreshold -> SpreadStatus.CLOSED_STOP
                else ->
                    spread.closeReason
                        ?.runCatching { SpreadStatus.valueOf(this) }
                        ?.getOrNull()
                        ?.takeIf { it != SpreadStatus.CLOSING }
                        ?: SpreadStatus.CLOSED_MANUAL
            }

        logger.info { "[${spread.symbol}] Market-closing stuck CLOSING spread (target=$actualStatus, value=\$$currentSpreadValue)" }
        forceCloseSpread(spread, currentSpreadValue, actualStatus, actualStatus.name, exitContext)

        if (actualStatus == SpreadStatus.CLOSED_STOP) {
            executionPort.blockEntry(spread.symbol, Duration.ofHours(config.stopLossCooldownHours))
        }
    }

    private suspend fun checkSpreadExit(spread: BullPutSpread) {
        val today = LocalDate.now(clock)
        val expiry = spread.soldLeg.contract.expiry
        val dte = ChronoUnit.DAYS.between(today, expiry).toInt()

        // Fetch current mid-prices for both legs
        val soldMid = marketDataPort.getOptionMid(spread.soldLeg.contract)
        val boughtMid = marketDataPort.getOptionMid(spread.boughtLeg.contract)
        val currentSpreadValue = soldMid.amount.subtract(boughtMid.amount)

        val inst = universePort.get(spread.symbol)
        val takeProfitPercent = inst?.takeProfitPercent ?: config.takeProfitPercent
        val stopLossPercent = inst?.stopLossPercent ?: config.stopLossPercent
        val timeProfitDte = inst?.timeProfitDte ?: config.timeProfitDte

        val tpThreshold = spread.creditPerShare.multiply(BigDecimal.ONE.subtract(BigDecimal(takeProfitPercent)))
        val slThreshold = spread.creditPerShare.add(spread.creditPerShare.multiply(BigDecimal(stopLossPercent)))

        logger.debug {
            "[${spread.symbol}] spread value=\$$currentSpreadValue credit=\$${"%.4f".format(spread.creditPerShare)} " +
                "TP≤\$${"%.4f".format(tpThreshold)} SL≥\$${"%.4f".format(slThreshold)} DTE=$dte"
        }

        val exitSignal: Pair<SpreadStatus, String>? =
            when {
                currentSpreadValue <= tpThreshold ->
                    SpreadStatus.CLOSED_PROFIT to "TP: spread value \$$currentSpreadValue ≤ \$$tpThreshold"
                currentSpreadValue >= slThreshold ->
                    SpreadStatus.CLOSED_STOP to "SL: spread value \$$currentSpreadValue ≥ \$$slThreshold"
                dte <= timeProfitDte ->
                    SpreadStatus.CLOSED_TIME to "DTE: $dte ≤ $timeProfitDte"
                else -> null
            }

        if (exitSignal == null) {
            spreadPort.update(spread.copy(lastSpreadValue = currentSpreadValue))
            return
        }

        val (closeStatus, reason) = exitSignal
        val exitContext = captureExitContext(spread)

        logger.info { "[${spread.symbol}] Closing spread at market — $reason" }
        forceCloseSpread(spread, currentSpreadValue, closeStatus, reason, exitContext)

        if (closeStatus == SpreadStatus.CLOSED_STOP) {
            executionPort.blockEntry(spread.symbol, Duration.ofHours(config.stopLossCooldownHours))
        }
    }

    private suspend fun captureExitContext(spread: BullPutSpread): Pair<BigDecimal?, BigDecimal?> {
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

    private fun roundToTick(price: BigDecimal): BigDecimal {
        val tick = if (price < BigDecimal("3.00")) BigDecimal("0.01") else BigDecimal("0.05")
        return price.divide(tick, 0, RoundingMode.HALF_UP).multiply(tick).setScale(2)
    }

    private suspend fun closeSpread(
        spread: BullPutSpread,
        closeStatus: SpreadStatus,
        soldMid: Money,
        boughtMid: Money,
        currentSpreadValue: BigDecimal,
        exitContext: Pair<BigDecimal?, BigDecimal?> = Pair(null, null),
    ) {
        val roundedSoldMid = Money(roundToTick(soldMid.amount))
        val roundedBoughtMid = Money(roundToTick(boughtMid.amount))

        // Mark CLOSING immediately so the scanner won't re-enter this symbol while orders are in flight
        val closing =
            spread.copy(
                status = SpreadStatus.CLOSING,
                closeReason = closeStatus.name,
                closePricePerShare = currentSpreadValue,
            )
        spreadPort.update(closing)

        // Sold leg is priced at zero — options are effectively worthless. Placing a buy limit at $0.00
        // will never fill. Skip the buy-back and close directly.
        if (roundedSoldMid.amount <= BigDecimal.ZERO) {
            val closed =
                closing.copy(
                    status = closeStatus,
                    closedAt = Instant.now(clock),
                    closeReason = closeStatus.name,
                    underlyingPriceAtExit = exitContext.first,
                    ivRankAtExit = exitContext.second,
                )
            spreadPort.update(closed)
            logger.info { "[${spread.symbol}] Sold leg worthless — closed $closeStatus without buy-back" }
            tradeLogger.info {
                "EXIT   ${spread.symbol}  ${spread.soldLeg.contract.strike}P/${spread.boughtLeg.contract.strike}P" +
                    "  exp=${spread.soldLeg.contract.expiry}  reason=${closeStatus.name}  value=\$0 (worthless)" +
                    "  credit=\$${spread.creditPerShare}  pnl=\$${spread.creditPerShare.multiply(
                        BigDecimal("100"),
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
                "CLOSING ${spread.symbol}  ${spread.soldLeg.contract.strike}P/${spread.boughtLeg.contract.strike}P  reason=buy_back_unfilled"
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
            logger.warn {
                "[${spread.symbol}] Sell-back of bought put did not fill after chase — left as CLOSING; sold leg already closed, long put remains"
            }
            tradeLogger.info {
                "CLOSING ${spread.symbol}  ${spread.soldLeg.contract.strike}P/${spread.boughtLeg.contract.strike}P  reason=sell_back_unfilled"
            }
            return
        }

        spreadPort.update(
            closing.copy(
                status = closeStatus,
                closedAt = Instant.now(clock),
                closeReason = closeStatus.name,
                underlyingPriceAtExit = exitContext.first,
                ivRankAtExit = exitContext.second,
            ),
        )
        logger.info { "[${spread.symbol}] Spread closed: $closeStatus at \$$currentSpreadValue" }
        tradeLogger.info {
            "EXIT   ${spread.symbol}  ${spread.soldLeg.contract.strike}P/${spread.boughtLeg.contract.strike}P" +
                "  exp=${spread.soldLeg.contract.expiry}  reason=${closeStatus.name}  value=\$$currentSpreadValue" +
                "  credit=\$${spread.creditPerShare}  pnl=\$${spread.creditPerShare.subtract(
                    currentSpreadValue,
                ).multiply(BigDecimal("100")).multiply(BigDecimal(spread.quantity))}"
        }
    }
}
