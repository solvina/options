package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.flag.config.FlagTradingConfigPort
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.flag.model.isTerminal
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("FLAG_TRADES")

@Service
class FlagManagementService(
    private val flagPort: FlagPort,
    private val bracketOrderPort: BracketOrderPort,
    private val flagTradingConfigPort: FlagTradingConfigPort,
    private val marketDataPort: MarketDataPort,
    private val clock: Clock,
) {
    sealed interface ManualCloseResult {
        data class Closed(val position: FlagPosition) : ManualCloseResult
        data object NotFound : ManualCloseResult
        data class AlreadyClosed(val position: FlagPosition) : ManualCloseResult
    }

    // -------------------------------------------------------------------------
    // EOD auto-liquidation
    // -------------------------------------------------------------------------

    /**
     * Called on a schedule near end-of-day. Cancels bracket children and market-sells
     * OPEN positions for the given market session (or all sessions when null).
     */
    suspend fun checkEodLiquidation(session: String? = null) {
        val open = flagPort.findOpen()
        val toClose = when {
            session == null -> open
            session == "EU" -> open.filter { it.marketSession == "EU" }
            else -> open.filter { it.marketSession == session || it.marketSession == null }
        }
        if (toClose.isEmpty()) return

        for (position in toClose) {
            logger.info { "[${position.symbol}] EOD liquidation (session=${session ?: "ALL"}): cancelling bracket orders and selling ${position.shares} shares" }
            performClose(position, FlagStatus.CLOSED_EOD, "eod_liquidation")
        }
    }

    // -------------------------------------------------------------------------
    // Manual close
    // -------------------------------------------------------------------------

    suspend fun manualClose(id: UUID): ManualCloseResult {
        val position = flagPort.findById(id) ?: return ManualCloseResult.NotFound
        if (position.status.isTerminal) return ManualCloseResult.AlreadyClosed(position)

        val closed = performClose(position, FlagStatus.CLOSED_MANUAL, "manual")
        return ManualCloseResult.Closed(closed)
    }

    // -------------------------------------------------------------------------
    // Scanner enable/disable (persisted)
    // -------------------------------------------------------------------------

    suspend fun pauseScanner() {
        val config = flagTradingConfigPort.get()
        flagTradingConfigPort.update(config.copy(enabled = false))
        logger.info { "Flag scanner paused" }
    }

    suspend fun resumeScanner() {
        val config = flagTradingConfigPort.get()
        flagTradingConfigPort.update(config.copy(enabled = true))
        logger.info { "Flag scanner resumed" }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Watermark tracking
    // -------------------------------------------------------------------------

    suspend fun updateWatermarks() {
        val open = flagPort.findOpen()
        for (position in open) {
            val price = runCatching { marketDataPort.getUnderlyingPrice(position.symbol).amount }.getOrNull() ?: continue
            val newHigh = position.highestPriceSeen?.max(price) ?: price
            val newLow = position.lowestPriceSeen?.min(price) ?: price
            if (newHigh != position.highestPriceSeen || newLow != position.lowestPriceSeen) {
                flagPort.update(position.copy(highestPriceSeen = newHigh, lowestPriceSeen = newLow))
            }
        }
    }

    suspend fun updateWatermarksForSymbol(symbol: Symbol, price: BigDecimal) {
        val open = flagPort.findOpen().filter { it.symbol == symbol }
        for (position in open) {
            val newHigh = position.highestPriceSeen?.max(price) ?: price
            val newLow = position.lowestPriceSeen?.min(price) ?: price
            if (newHigh != position.highestPriceSeen || newLow != position.lowestPriceSeen) {
                flagPort.update(position.copy(highestPriceSeen = newHigh, lowestPriceSeen = newLow))
            }
        }
    }

    private suspend fun performClose(
        position: FlagPosition,
        closeStatus: FlagStatus,
        reason: String,
    ): FlagPosition {
        // Always cancel the children (safe if already cancelled by OCA)
        runCatching { bracketOrderPort.cancelOrder(position.stopLossOrderId) }
            .onFailure { e -> logger.warn { "[${position.symbol}] SL cancel failed (may already be inactive): ${e.message}" } }
        runCatching { bracketOrderPort.cancelOrder(position.profitTargetOrderId) }
            .onFailure { e -> logger.warn { "[${position.symbol}] PT cancel failed (may already be inactive): ${e.message}" } }

        var closePriceActual: BigDecimal? = null
        var realizedPnl: BigDecimal? = null

        if (position.status == FlagStatus.OPEN) {
            // Place market sell to exit the live equity position
            runCatching { bracketOrderPort.submitMarketSell(position.symbol, position.shares) }
                .onFailure { e -> logger.error(e) { "[${position.symbol}] Market sell failed: ${e.message}" } }

            // Best-effort price capture
            closePriceActual = runCatching {
                marketDataPort.getUnderlyingPrice(position.symbol).amount
            }.getOrNull()

            val effectiveEntry = position.actualEntryPrice ?: position.entryPrice
            if (closePriceActual != null) {
                realizedPnl = closePriceActual
                    .subtract(effectiveEntry)
                    .multiply(BigDecimal(position.shares))
                    .setScale(2, RoundingMode.HALF_UP)
            }
        }

        val shares = BigDecimal(position.shares)
        val effectiveEntry = position.actualEntryPrice ?: position.entryPrice
        val mfe = position.highestPriceSeen
            ?.subtract(effectiveEntry)
            ?.multiply(shares)
            ?.setScale(2, RoundingMode.HALF_UP)
        val mae = position.lowestPriceSeen
            ?.let { effectiveEntry.subtract(it) }
            ?.multiply(shares)
            ?.setScale(2, RoundingMode.HALF_UP)

        val closedAt = Instant.now(clock)
        val rMultiple = if (realizedPnl != null && position.riskAmount > BigDecimal.ZERO)
            realizedPnl.divide(position.riskAmount, 2, RoundingMode.HALF_UP) else null
        val mfeR = if (mfe != null && position.riskAmount > BigDecimal.ZERO)
            mfe.divide(position.riskAmount, 2, RoundingMode.HALF_UP) else null
        val maeR = if (mae != null && position.riskAmount > BigDecimal.ZERO)
            mae.divide(position.riskAmount, 2, RoundingMode.HALF_UP) else null
        val timeInTrade = ChronoUnit.SECONDS.between(position.openedAt, closedAt).toInt()

        val closed = position.copy(
            status = closeStatus,
            closedAt = closedAt,
            closeReason = reason,
            closePriceActual = closePriceActual,
            realizedPnl = realizedPnl,
            maxFavorableExcursion = mfe,
            maxAdverseExcursion = mae,
            rMultiple = rMultiple,
            mfeR = mfeR,
            maeR = maeR,
            timeInTradeSeconds = timeInTrade,
        )
        flagPort.update(closed)
        tradeLogger.info { "${closeStatus.name} ${position.symbol} reason=$reason pnl=${realizedPnl ?: "n/a"} R=$rMultiple" }
        return closed
    }
}
