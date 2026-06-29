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
 * Classifies a symbol's trend regime from daily closes (SMA-fast vs SMA-slow alignment).
 *
 * [regimeFor] is a cache read only — it never fetches, so it's safe on the trading/scan path.
 * [refresh] does the (rate-limited) price-history fetch and is driven off-path by the scheduled
 * warmup. Fail-open: any error / insufficient history → NEUTRAL. Observe-only today.
 */
@Service
class TrendRegimeService(
    private val priceHistoryPort: PriceHistoryPort,
    private val config: RegimeConfig,
) {
    private val cache = ConcurrentHashMap<String, RegimeSignal>()

    /** Latest cached regime, or NEUTRAL/unknown if not warmed yet. No fetch — never blocks. */
    fun regimeFor(symbol: Symbol): RegimeSignal = cache[symbol.value] ?: RegimeSignal(TrendRegime.NEUTRAL, null, null, null)

    /** Fetch price history, classify, and cache. Called by the scheduled warmup (off the scan path). */
    suspend fun refresh(symbol: Symbol): RegimeSignal =
        runCatching {
            val closes = priceHistoryPort.fetchDailyPriceBars(symbol, config.lookbackDays).toList().map { it.close }
            classifyRegime(closes, config.smaFast, config.smaSlow)
        }.getOrElse {
            logger.warn { "[$symbol] regime computation failed (fail-open NEUTRAL): ${it.message}" }
            RegimeSignal(TrendRegime.NEUTRAL, null, null, null)
        }.also { cache[symbol.value] = it }
}

/**
 * Pure regime classification from an oldest-first list of daily closes.
 * UPTREND: close > smaFast > smaSlow. DOWNTREND: close < smaFast < smaSlow. Else NEUTRAL.
 * Fewer than [slow] bars → NEUTRAL (fail-open).
 */
internal fun classifyRegime(
    closes: List<BigDecimal>,
    fast: Int,
    slow: Int,
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
    return RegimeSignal(regime, last, smaFast, smaSlow)
}

private fun List<BigDecimal>.average(): BigDecimal =
    if (isEmpty()) BigDecimal.ZERO else reduce(BigDecimal::add).divide(BigDecimal(size), 4, RoundingMode.HALF_UP)
