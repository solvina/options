package cz.solvina.options.domain.features.spread

import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.order.OrderPort
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Money
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class SpreadManagementService(
    private val spreadPort: SpreadPort,
    private val marketDataPort: MarketDataPort,
    private val orderPort: OrderPort,
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

    private suspend fun manualClose(
        id: UUID,
        useMarket: Boolean,
    ): ManualCloseResult {
        val spread = spreadPort.findById(id) ?: return ManualCloseResult.NotFound
        if (spread.status != SpreadStatus.OPEN) return ManualCloseResult.AlreadyClosed(spread)

        val soldMid = marketDataPort.getOptionMid(spread.soldLeg.contract)
        val boughtMid = marketDataPort.getOptionMid(spread.boughtLeg.contract)
        val spreadValue = soldMid.amount.subtract(boughtMid.amount)

        val closed =
            if (useMarket) {
                forceCloseSpread(spread, spreadValue)
            } else {
                closeSpread(spread, SpreadStatus.CLOSED_MANUAL, soldMid, boughtMid, spreadValue)
                spread.copy(
                    status = SpreadStatus.CLOSED_MANUAL,
                    closedAt = Instant.now(clock),
                    closeReason = SpreadStatus.CLOSED_MANUAL.name,
                    closePricePerShare = spreadValue,
                )
            }
        return ManualCloseResult.Closed(closed)
    }

    private suspend fun forceCloseSpread(
        spread: BullPutSpread,
        estimatedSpreadValue: BigDecimal,
    ): BullPutSpread {
        logger.info { "[${spread.symbol}] Force-closing at market" }
        orderPort.placeMarketOrder(spread.soldLeg.contract, LegAction.BUY, spread.quantity)
        orderPort.placeMarketOrder(spread.boughtLeg.contract, LegAction.SELL, spread.quantity)
        val updated =
            spread.copy(
                status = SpreadStatus.CLOSED_MANUAL,
                closedAt = Instant.now(clock),
                closeReason = "MANUAL_FORCE",
                closePricePerShare = estimatedSpreadValue,
            )
        spreadPort.update(updated)
        logger.info { "[${spread.symbol}] Force-closed. Estimated value \$$estimatedSpreadValue" }
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

        val tpThreshold = spread.creditPerShare.multiply(BigDecimal.ONE.subtract(BigDecimal(config.takeProfitPercent)))
        val slThreshold = spread.creditPerShare.add(spread.creditPerShare.multiply(BigDecimal(config.stopLossPercent)))

        logger.debug {
            "[${spread.symbol}] spread value=\$$currentSpreadValue credit=\$${"%.4f".format(spread.creditPerShare)} " +
                "TP≤\$${"%.4f".format(tpThreshold)} SL≥\$${"%.4f".format(slThreshold)} DTE=$dte"
        }

        val (closeStatus, reason) =
            when {
                currentSpreadValue <= tpThreshold ->
                    SpreadStatus.CLOSED_PROFIT to "TP: spread value \$$currentSpreadValue ≤ \$$tpThreshold"
                currentSpreadValue >= slThreshold ->
                    SpreadStatus.CLOSED_STOP to "SL: spread value \$$currentSpreadValue ≥ \$$slThreshold"
                dte <= config.timeProfitDte ->
                    SpreadStatus.CLOSED_TIME to "DTE: $dte ≤ ${config.timeProfitDte}"
                else -> return
            }

        logger.info { "[${spread.symbol}] Closing spread — $reason" }
        closeSpread(spread, closeStatus, soldMid, boughtMid, currentSpreadValue)
    }

    private suspend fun closeSpread(
        spread: BullPutSpread,
        closeStatus: SpreadStatus,
        soldMid: Money,
        boughtMid: Money,
        currentSpreadValue: BigDecimal,
    ) {
        // Buy back sold put
        val buyBackOrder =
            orderPort.placeAndAwaitFill(
                contract = spread.soldLeg.contract,
                action = LegAction.BUY,
                limitPrice = soldMid,
                qty = spread.quantity,
            )
        if (buyBackOrder.status != OrderStatus.FILLED) {
            logger.warn { "[${spread.symbol}] Buy-back of sold put did not fill, skipping close" }
            return
        }

        // Sell back bought put
        val sellBackOrder =
            orderPort.placeAndAwaitFill(
                contract = spread.boughtLeg.contract,
                action = LegAction.SELL,
                limitPrice = boughtMid,
                qty = spread.quantity,
            )
        if (sellBackOrder.status != OrderStatus.FILLED) {
            logger.warn { "[${spread.symbol}] Sell-back of bought put did not fill, spread NOT closed" }
            return
        }

        val updated =
            spread.copy(
                status = closeStatus,
                closedAt = Instant.now(clock),
                closeReason = closeStatus.name,
                closePricePerShare = currentSpreadValue,
            )
        spreadPort.update(updated)
        logger.info { "[${spread.symbol}] Spread closed: $closeStatus at \$$currentSpreadValue" }
    }
}
