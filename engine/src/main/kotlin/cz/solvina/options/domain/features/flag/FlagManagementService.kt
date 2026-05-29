package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.flag.config.FlagTradingConfigPort
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.flag.model.isTerminal
import cz.solvina.options.domain.features.market.MarketDataPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
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
     * all OPEN positions that should be liquidated before exchange close.
     */
    suspend fun checkEodLiquidation() {
        val config = flagTradingConfigPort.get()
        val open = flagPort.findOpen()
        if (open.isEmpty()) return

        for (position in open) {
            logger.info { "[${position.symbol}] EOD liquidation: cancelling bracket orders and selling ${position.shares} shares" }
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

            if (closePriceActual != null) {
                realizedPnl = closePriceActual
                    .subtract(position.entryPrice)
                    .multiply(BigDecimal(position.shares))
                    .setScale(2, RoundingMode.HALF_UP)
            }
        }

        val closed = position.copy(
            status = closeStatus,
            closedAt = Instant.now(clock),
            closeReason = reason,
            closePriceActual = closePriceActual,
            realizedPnl = realizedPnl,
        )
        flagPort.update(closed)
        tradeLogger.info { "${closeStatus.name} ${position.symbol} reason=$reason pnl=${realizedPnl ?: "n/a"}" }
        return closed
    }
}
