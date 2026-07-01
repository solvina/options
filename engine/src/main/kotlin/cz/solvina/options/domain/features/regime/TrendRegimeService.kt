package cz.solvina.options.domain.features.regime

import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Classifies a symbol's directional bias from daily closes: trend (SMA-fast vs SMA-slow) plus
 * momentum (RSI), combined via [directionalBias].
 *
 * [regimeFor]/[biasFor] are cache reads only — they never fetch, so they're safe on the trading/scan
 * path. [refresh] does the (rate-limited) price-history fetch and is driven off-path by the scheduled
 * warmup. Fail-open: any error / insufficient history → NEUTRAL. [gatingEnabled] decides whether the
 * scanner actually uses the bias to pick a strategy or just logs it (observe-only).
 */
@Service
class TrendRegimeService(
    private val priceHistoryPort: PriceHistoryPort,
    private val config: RegimeConfig,
) {
    private val cache = ConcurrentHashMap<String, RegimeSignal>()

    /** Latest cached regime, or NEUTRAL/unknown if not warmed yet. No fetch — never blocks. */
    fun regimeFor(symbol: Symbol): RegimeSignal = cache[symbol.value] ?: RegimeSignal(TrendRegime.NEUTRAL, null, null, null)

    /** Latest cached directional bias (bull put = BULLISH, bear call = BEARISH). No fetch. */
    fun biasFor(symbol: Symbol): DirectionalBias = regimeFor(symbol).bias

    /** Whether the scanner should let the bias gate strategy selection (vs observe-only logging). */
    fun gatingEnabled(): Boolean = config.gatingEnabled

    /** Fetch price history, classify, and cache. Called by the scheduled warmup (off the scan path). */
    suspend fun refresh(symbol: Symbol): RegimeSignal =
        runCatching {
            val closes = priceHistoryPort.fetchDailyPriceBars(symbol, config.lookbackDays).toList().map { it.close }
            classifyRegime(closes, config.smaFast, config.smaSlow, config.rsiPeriod, config.rsiOverbought, config.rsiOversold)
        }.getOrElse {
            logger.warn { "[$symbol] regime computation failed (fail-open NEUTRAL): ${it.message}" }
            RegimeSignal(TrendRegime.NEUTRAL, null, null, null)
        }.also { cache[symbol.value] = it }
}

/**
 * Pure classification from an oldest-first list of daily closes.
 * UPTREND: close > smaFast > smaSlow. DOWNTREND: close < smaFast < smaSlow. Else NEUTRAL.
 * Fewer than [slow] bars → NEUTRAL (fail-open). Also computes RSI([rsiPeriod]) and the resulting
 * [DirectionalBias]; RSI params are defaulted so trend-only callers/tests are unaffected.
 */
internal fun classifyRegime(
    closes: List<BigDecimal>,
    fast: Int,
    slow: Int,
    rsiPeriod: Int = 14,
    rsiOverbought: Double = 70.0,
    rsiOversold: Double = 30.0,
): RegimeSignal {
    if (closes.size < slow) return RegimeSignal(TrendRegime.NEUTRAL, closes.lastOrNull(), null, null)
    val last = closes.last()
    val smaFast = closes.takeLast(fast).average()
    val smaSlow = closes.takeLast(slow).average()
    val regime =
        when {
            last > smaFast && smaFast > smaSlow -> TrendRegime.UPTREND
            last < smaFast && smaFast < smaSlow -> TrendRegime.DOWNTREND
            else -> TrendRegime.NEUTRAL
        }
    val rsi = computeRsi(closes, rsiPeriod)
    val bias = directionalBias(regime, rsi, rsiOverbought, rsiOversold)
    return RegimeSignal(regime, last, smaFast, smaSlow, rsi, bias)
}

/**
 * Wilder's RSI over an oldest-first close series. Returns null with fewer than [period]+1 bars.
 * All-gains → 100, all-flat → 50 (neutral). Kept in Double for the smoothing math, scaled to 2dp.
 */
internal fun computeRsi(
    closes: List<BigDecimal>,
    period: Int,
): BigDecimal? {
    if (period < 1 || closes.size < period + 1) return null
    var avgGain = 0.0
    var avgLoss = 0.0
    for (i in 1..period) {
        val diff = closes[i].toDouble() - closes[i - 1].toDouble()
        if (diff >= 0) avgGain += diff else avgLoss += -diff
    }
    avgGain /= period
    avgLoss /= period
    for (i in period + 1 until closes.size) {
        val diff = closes[i].toDouble() - closes[i - 1].toDouble()
        val gain = if (diff > 0) diff else 0.0
        val loss = if (diff < 0) -diff else 0.0
        avgGain = (avgGain * (period - 1) + gain) / period
        avgLoss = (avgLoss * (period - 1) + loss) / period
    }
    if (avgLoss == 0.0) return if (avgGain == 0.0) BigDecimal("50.00") else BigDecimal("100.00")
    val rs = avgGain / avgLoss
    return BigDecimal(100.0 - 100.0 / (1.0 + rs)).setScale(2, RoundingMode.HALF_UP)
}

private fun List<BigDecimal>.average(): BigDecimal =
    if (isEmpty()) BigDecimal.ZERO else reduce(BigDecimal::add).divide(BigDecimal(size), 4, RoundingMode.HALF_UP)
