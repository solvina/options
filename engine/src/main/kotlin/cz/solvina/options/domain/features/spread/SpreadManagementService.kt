package cz.solvina.options.domain.features.spread

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
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
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
    private val config: ScannerConfig,
    private val clock: Clock,
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
        // Fire both legs; ignore fill-wait timeout — orders are already submitted at IBKR
        runCatching { orderPort.placeMarketOrder(spread.soldLeg.contract, LegAction.BUY, spread.quantity) }
            .onFailure { e -> logger.warn { "[${spread.symbol}] Sold-leg market order timed out (order still submitted): ${e.message}" } }
        runCatching { orderPort.placeMarketOrder(spread.boughtLeg.contract, LegAction.SELL, spread.quantity) }
            .onFailure { e -> logger.warn { "[${spread.symbol}] Bought-leg market order timed out (order still submitted): ${e.message}" } }
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

    suspend fun checkExits() {
        val openSpreads = spreadPort.findOpen()
        if (openSpreads.isEmpty()) {
            logger.debug { "No open spreads to monitor" }
            return
        }

        logger.info { "Checking exits for ${openSpreads.size} open spread(s)" }
        for (spread in openSpreads) {
            runCatching { checkSpreadExit(spread) }
                .onFailure { e -> logger.error(e) { "[${spread.symbol}] Error checking spread exit: ${e.message}" } }
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

        logger.info { "[${spread.symbol}] Closing spread — $reason" }
        closeSpread(spread, closeStatus, soldMid, boughtMid, currentSpreadValue, exitContext)
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
                    "  credit=\$${spread.creditPerShare}  pnl=\$${spread.creditPerShare.multiply(BigDecimal("100")).multiply(BigDecimal(spread.quantity))}"
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
