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

private val logger = KotlinLogging.logger {}

@Service
class SpreadManagementService(
    private val spreadPort: SpreadPort,
    private val marketDataPort: MarketDataPort,
    private val orderPort: OrderPort,
    private val config: ScannerConfig,
    private val clock: Clock,
) {
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
        val slThreshold = spread.creditPerShare.add(spread.maxRiskPerShare.multiply(BigDecimal(config.stopLossPercent)))

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
        closeSpread(spread, closeStatus, currentSpreadValue)
    }

    private suspend fun closeSpread(
        spread: BullPutSpread,
        closeStatus: SpreadStatus,
        currentSpreadValue: BigDecimal,
    ) {
        // Buy back sold put
        val buyBackOrder =
            orderPort.placeAndAwaitFill(
                contract = spread.soldLeg.contract,
                action = LegAction.BUY,
                limitPrice = Money(currentSpreadValue),
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
                limitPrice =
                    Money(
                        spread.boughtLeg.contract.strike
                            .multiply(BigDecimal.ZERO),
                    ),
                qty = spread.quantity,
            )
        if (sellBackOrder.status != OrderStatus.FILLED) {
            logger.warn { "[${spread.symbol}] Sell-back of bought put did not fill" }
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
