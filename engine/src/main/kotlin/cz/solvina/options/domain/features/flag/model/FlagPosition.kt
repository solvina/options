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
    // ---- Pattern quality at entry ----
    val flagBarCount: Int? = null,
    val flagpoleBarCount: Int? = null,
    val flagpoleAvgVolume: Long? = null,
    val flagAvgVolume: Long? = null,
    /** Slope of the upper resistance regression line (negative = descending channel = tighter flag). */
    val channelSlope: BigDecimal? = null,
    // ---- Market context at entry ----
    val marketSession: String? = null,
    val minutesToClose: Int? = null,
    /** actualEntryPrice − entryPrice; negative means filled better than expected. */
    val entrySlippage: BigDecimal? = null,
    /** realizedPnl / riskAmount — e.g. 2.0 = 2R win, −1.0 = full stop loss. */
    val rMultiple: BigDecimal? = null,
    val timeInTradeSeconds: Int? = null,
    /** 14-bar ATR on 5-min candles at the moment of entry signal. */
    val atrAtEntry: BigDecimal? = null,
    /** 20-bar volume moving average — denominator for all volume ratio analysis. */
    val volumeMaAtEntry: Long? = null,
    /** flagpoleAvgVolume / volumeMaAtEntry — how many times normal volume was the pole. */
    val flagpoleVolumeRatio: BigDecimal? = null,
    /** Intraday VWAP at entry. Price above VWAP = bullish context. */
    val vwapAtEntry: BigDecimal? = null,
    /** First bar open of the current session — sets the intraday trend reference. */
    val dayOpenPrice: BigDecimal? = null,
    /** "FIVE_MIN" when breakout confirmed on 5-min close; "LIVE_BAR" when fired on sub-candle 5-sec close. */
    val breakoutType: String? = null,
    /** (entryPrice − stopLossPrice) / entryPrice × 100 */
    val stopDistancePct: BigDecimal? = null,
    /** maxFavorableExcursion / riskAmount — how far did price move in our favour. */
    val mfeR: BigDecimal? = null,
    /** maxAdverseExcursion / riskAmount — how close to the stop did price get. */
    val maeR: BigDecimal? = null,
)
