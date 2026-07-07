package cz.solvina.options.domain.features.flag

import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.flag.config.FlagTradingConfigPort
import cz.solvina.options.domain.features.flag.model.FlagPosition
import cz.solvina.options.domain.features.flag.model.FlagStatus
import cz.solvina.options.domain.features.flag.model.isTerminal
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private val logger = KotlinLogging.logger {}
private val tradeLogger = KotlinLogging.logger("FLAG_TRADES")

/** Attempts to obtain a trustworthy broker position snapshot before a close sells anything. */
private const val CLOSE_POSITION_POLLS = 3
private const val CLOSE_POSITION_POLL_DELAY_MS = 300L

@Service
class FlagManagementService(
    private val flagPort: FlagPort,
    private val bracketOrderPort: BracketOrderPort,
    private val flagTradingConfigPort: FlagTradingConfigPort,
    private val marketDataPort: MarketDataPort,
    private val positionsPort: PositionsPort,
    private val clock: Clock,
) {
    sealed interface ManualCloseResult {
        data class Closed(
            val position: FlagPosition,
        ) : ManualCloseResult

        data object NotFound : ManualCloseResult

        data class AlreadyClosed(
            val position: FlagPosition,
        ) : ManualCloseResult

        /** Close aborted before any order was placed — broker holdings could not be verified. */
        data class Failed(
            val position: FlagPosition,
            val reason: String,
        ) : ManualCloseResult
    }

    // -------------------------------------------------------------------------
    // EOD auto-liquidation
    // -------------------------------------------------------------------------

    /**
     * Called on a schedule near end-of-day. Cancels bracket children and market-sells
     * OPEN positions for the given market session (or all sessions when null).
     */
    suspend fun checkEodLiquidation(session: String? = null) {
        // Hold-overnight mode (best config): eodLiqMinutesBeforeClose <= 0 disables EOD liquidation,
        // so trailing-stop positions ride to their stop/target across days instead of being force-
        // closed at the bell. Set the config value to 0 to enable hold-overnight.
        if (flagTradingConfigPort.get().eodLiqMinutesBeforeClose <= 0) {
            logger.debug { "EOD liquidation disabled (hold-overnight) — skipping" }
            return
        }
        val open = flagPort.findOpen()
        val toClose =
            when {
                session == null -> open
                session == "EU" -> open.filter { it.marketSession == "EU" }
                else -> open.filter { it.marketSession == session || it.marketSession == null }
            }
        if (toClose.isEmpty()) return

        for (position in toClose) {
            logger.info {
                "[${position.symbol}] EOD liquidation (session=${session ?: "ALL"}): cancelling bracket orders and selling ${position.shares} shares"
            }
            if (performClose(position, FlagStatus.CLOSED_EOD, "eod_liquidation") == null) {
                logger.error { "[${position.symbol}] EOD liquidation skipped — broker holdings unverifiable; will retry next run" }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Manual close
    // -------------------------------------------------------------------------

    suspend fun manualClose(id: UUID): ManualCloseResult {
        val position = flagPort.findById(id) ?: return ManualCloseResult.NotFound
        if (position.status.isTerminal) return ManualCloseResult.AlreadyClosed(position)

        val closed =
            performClose(position, FlagStatus.CLOSED_MANUAL, "manual")
                ?: return ManualCloseResult.Failed(position, "broker holdings unverifiable — nothing was sold, position left OPEN")
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

    suspend fun updateWatermarksForSymbol(
        symbol: Symbol,
        price: BigDecimal,
    ) {
        val open = flagPort.findOpen().filter { it.symbol == symbol }
        for (position in open) {
            val newHigh = position.highestPriceSeen?.max(price) ?: price
            val newLow = position.lowestPriceSeen?.min(price) ?: price
            if (newHigh != position.highestPriceSeen || newLow != position.lowestPriceSeen) {
                flagPort.update(position.copy(highestPriceSeen = newHigh, lowestPriceSeen = newLow))
            }
        }
    }

    /**
     * Closes [position]: cancels its working orders and market-sells whatever the broker actually
     * still holds. Returns null (close aborted, nothing changed) when the position is OPEN but the
     * broker position snapshot could not be verified — selling blind is how a stale OPEN row whose
     * trailing stop already filled turns into a short stock orphan (AAPL/META/TSLA/GOOGL incident,
     * 2026-07-01/06).
     */
    private suspend fun performClose(
        position: FlagPosition,
        closeStatus: FlagStatus,
        reason: String,
    ): FlagPosition? {
        // Verify holdings BEFORE cancelling anything: aborting later would leave the position
        // unprotected (children cancelled) yet still OPEN.
        val heldShares =
            if (position.status == FlagStatus.OPEN) {
                fetchHeldShares(position.symbol) ?: return null
            } else {
                null
            }

        // Always cancel the children (safe if already cancelled by OCA); for a PENDING position
        // also the entry order, otherwise it can fill later and leave an untracked long position.
        if (position.status == FlagStatus.PENDING) {
            runCatching { bracketOrderPort.cancelOrder(position.entryOrderId) }
                .onFailure { e -> logger.warn { "[${position.symbol}] Entry cancel failed (may already be inactive): ${e.message}" } }
        }
        runCatching { bracketOrderPort.cancelOrder(position.stopLossOrderId) }
            .onFailure { e -> logger.warn { "[${position.symbol}] SL cancel failed (may already be inactive): ${e.message}" } }
        runCatching { bracketOrderPort.cancelOrder(position.profitTargetOrderId) }
            .onFailure { e -> logger.warn { "[${position.symbol}] PT cancel failed (may already be inactive): ${e.message}" } }

        var closePriceActual: BigDecimal? = null
        var realizedPnl: BigDecimal? = null
        var effectiveStatus = closeStatus
        var effectiveReason = reason

        if (position.status == FlagStatus.OPEN) {
            // Cap the sell at what the broker actually holds. There is still a small race (a fill
            // between the snapshot and the sell), but the days-wide hole — selling a position whose
            // exit filled while nobody was watching — is closed.
            val sellQty = minOf(position.shares, maxOf(0, heldShares!!))

            if (sellQty == 0) {
                logger.warn {
                    "[${position.symbol}] Close requested ($reason) but broker holds no shares (net=$heldShares, " +
                        "expected ${position.shares}) — exit already filled externally; closing administratively, NOT selling"
                }
                effectiveStatus = FlagStatus.CLOSED_EXTERNAL
                effectiveReason = "external_exit_detected($reason)"
            } else {
                if (sellQty < position.shares) {
                    logger.warn {
                        "[${position.symbol}] Broker holds only $sellQty of ${position.shares} expected shares — " +
                            "selling the held quantity only"
                    }
                }
                runCatching { bracketOrderPort.submitMarketSell(position.symbol, sellQty) }
                    .onFailure { e -> logger.error(e) { "[${position.symbol}] Market sell failed: ${e.message}" } }

                // Best-effort price capture
                closePriceActual =
                    runCatching {
                        marketDataPort.getUnderlyingPrice(position.symbol).amount
                    }.getOrNull()

                val effectiveEntry = position.actualEntryPrice ?: position.entryPrice
                if (closePriceActual != null) {
                    realizedPnl =
                        closePriceActual
                            .subtract(effectiveEntry)
                            .multiply(BigDecimal(sellQty))
                            .setScale(2, RoundingMode.HALF_UP)
                }
            }
        }

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

        val closedAt = Instant.now(clock)
        val rMultiple =
            if (realizedPnl != null && position.riskAmount > BigDecimal.ZERO) {
                realizedPnl.divide(position.riskAmount, 2, RoundingMode.HALF_UP)
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
        val timeInTrade = ChronoUnit.SECONDS.between(position.openedAt, closedAt).toInt()

        val closed =
            position.copy(
                status = effectiveStatus,
                closedAt = closedAt,
                closeReason = effectiveReason,
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
        tradeLogger.info { "${effectiveStatus.name} ${position.symbol} reason=$effectiveReason pnl=${realizedPnl ?: "n/a"} R=$rMultiple" }
        return closed
    }

    /**
     * Net STK quantity the broker holds for [symbol], or null when no trustworthy snapshot could
     * be obtained. An empty snapshot is treated as untrustworthy (a feed still warming up looks
     * identical to a flat account), so callers abort rather than conclude "nothing held".
     */
    private suspend fun fetchHeldShares(symbol: Symbol): Int? {
        repeat(CLOSE_POSITION_POLLS) { attempt ->
            val snapshot =
                runCatching { positionsPort.getPositions() }
                    .onFailure { e -> logger.warn { "[$symbol] Position fetch failed (attempt $attempt): ${e.message}" } }
                    .getOrNull()
            if (!snapshot.isNullOrEmpty()) {
                return snapshot
                    .filter { it.secType == "STK" && it.symbol == symbol.value }
                    .sumOf { it.quantity }
                    .toInt()
            }
            if (attempt < CLOSE_POSITION_POLLS - 1) delay(CLOSE_POSITION_POLL_DELAY_MS)
        }
        logger.error { "[$symbol] No trustworthy broker position snapshot after $CLOSE_POSITION_POLLS attempts" }
        return null
    }
}
