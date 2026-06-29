package cz.solvina.options.domain.features.regime

import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Classifies a symbol's trend regime from daily closes (SMA-fast vs SMA-slow alignment). Cached per
 * symbol (regime is slow-moving) and fail-open: any error / insufficient history → NEUTRAL, so it
 * never blocks. Observe-only today; a later phase uses it to gate entries by directional bias.
 */
@Service
class TrendRegimeService(
    private val priceHistoryPort: PriceHistoryPort,
    private val config: RegimeConfig,
    private val clock: Clock,
) {
    private data class Cached(
        val signal: RegimeSignal,
        val at: Instant,
    )

    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun regimeFor(symbol: Symbol): RegimeSignal {
        val now = Instant.now(clock)
        cache[symbol.value]?.let { if (Duration.between(it.at, now).toHours() < config.cacheTtlHours) return it.signal }
        val signal = compute(symbol)
        cache[symbol.value] = Cached(signal, now)
        return signal
    }

    private suspend fun compute(symbol: Symbol): RegimeSignal =
        runCatching {
            val closes = priceHistoryPort.fetchDailyPriceBars(symbol, config.lookbackDays).toList().map { it.close }
            classifyRegime(closes, config.smaFast, config.smaSlow)
        }.getOrElse {
            logger.warn { "[$symbol] regime computation failed (fail-open NEUTRAL): ${it.message}" }
            RegimeSignal(TrendRegime.NEUTRAL, null, null, null)
        }
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
