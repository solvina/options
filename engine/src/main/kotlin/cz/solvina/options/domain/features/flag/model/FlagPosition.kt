package cz.solvina.options.domain.features.flag.model

import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class FlagPosition(
    val id: UUID?,
    val symbol: Symbol,
    val status: FlagStatus,
    // ---- Bracket order IDs ----
    val entryOrderId: Int,
    val stopLossOrderId: Int,
    val profitTargetOrderId: Int,
    // ---- Prices ----
    /** Stop-market trigger price (breakout level). */
    val entryPrice: BigDecimal,
    /** Stop-loss = lowest point of flag consolidation, placed just below. */
    val stopLossPrice: BigDecimal,
    /** Profit target = entryPrice + 2 × (entryPrice − stopLossPrice). */
    val profitTargetPrice: BigDecimal,
    /** Number of shares. Calculated as riskAmount / (entryPrice − stopLossPrice). */
    val shares: Int,
    /** Configured risk per trade at the time of entry. */
    val riskAmount: BigDecimal,
    // ---- Pattern context snapshot at entry ----
    val flagpoleHeight: BigDecimal?,
    val flagRetracement: BigDecimal?,
    val resistanceAtEntry: BigDecimal?,
    val patternStartedAt: Instant?,
    // ---- Lifecycle ----
    val openedAt: Instant,
    val closedAt: Instant? = null,
    val closeReason: String? = null,
    val closePriceActual: BigDecimal? = null,
    val realizedPnl: BigDecimal? = null,
    // ---- Trade journaling ----
    val strategyName: String = "bull_flag",
    val actualEntryPrice: BigDecimal? = null,
    val highestPriceSeen: BigDecimal? = null,
    val lowestPriceSeen: BigDecimal? = null,
    val maxFavorableExcursion: BigDecimal? = null,
    val maxAdverseExcursion: BigDecimal? = null,
)
