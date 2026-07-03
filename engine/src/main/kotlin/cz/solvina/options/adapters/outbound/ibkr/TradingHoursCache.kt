package cz.solvina.options.adapters.outbound.ibkr

import cz.solvina.options.domain.features.universe.DaySession
import cz.solvina.options.domain.features.universe.MarketCalendarPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * In-memory trading calendar populated from IBKR `liquidHours` (regular-session) strings. Keyed by
 * symbol; each entry is the parsed per-date session for the window IBKR returns (typically today
 * plus the next several days). Reads are lock-free and synchronous so the hot-path [isMarketOpen]
 * check can consult it; a whole per-symbol map is replaced atomically on each [update].
 */
@Component
class TradingHoursCache : MarketCalendarPort {
    private val bySymbol = ConcurrentHashMap<Symbol, Map<LocalDate, DaySession>>()

    override fun sessionFor(
        symbol: Symbol,
        date: LocalDate,
    ): DaySession? = bySymbol[symbol]?.get(date)

    /** Replace [symbol]'s calendar from an IBKR liquidHours string. No-op on blank/unparseable input. */
    fun update(
        symbol: Symbol,
        liquidHours: String?,
    ) {
        if (liquidHours.isNullOrBlank()) return
        val parsed = parseLiquidHours(liquidHours)
        if (parsed.isNotEmpty()) {
            bySymbol[symbol] = parsed
            logger.debug { "[$symbol] trading calendar updated: ${parsed.size} day(s)" }
        }
    }

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

        /**
         * Parse an IBKR liquidHours string into a per-date session map. Format is `;`-separated
         * segments, each either `YYYYMMDD:CLOSED` (holiday/closed) or `YYYYMMDD:HHMM-YYYYMMDD:HHMM`
         * (or the older `YYYYMMDD:HHMM-HHMM`). Half-days appear naturally as a shortened window.
         * Unparseable segments are skipped; an open window always wins over a CLOSED marker for the
         * same date.
         */
        fun parseLiquidHours(raw: String): Map<LocalDate, DaySession> {
            val result = LinkedHashMap<LocalDate, DaySession>()
            for (segment in raw.split(';')) {
                val seg = segment.trim()
                if (seg.length < 8) continue
                val date = runCatching { LocalDate.parse(seg.take(8), DATE) }.getOrNull() ?: continue
                if (seg.uppercase().contains("CLOSED")) {
                    // Don't let a CLOSED marker overwrite open hours already recorded for this date.
                    result.putIfAbsent(date, DaySession.Closed)
                    continue
                }
                val parts = seg.substringAfter(':', "").split('-')
                if (parts.size != 2) continue
                val open = parseHhmm(parts[0]) ?: continue
                val close = parseHhmm(parts[1]) ?: continue
                val existing = result[date]
                result[date] =
                    if (existing is DaySession.Open) {
                        DaySession.Open(minOf(existing.open, open), maxOf(existing.close, close))
                    } else {
                        DaySession.Open(open, close)
                    }
            }
            return result
        }

        /** Parse a trailing HHMM out of a token like "1600" or "20260706:1600". */
        private fun parseHhmm(token: String): LocalTime? {
            val digits = token.trim().takeLastWhile { it.isDigit() }
            if (digits.length < 4) return null
            val hhmm = digits.takeLast(4)
            val h = hhmm.substring(0, 2).toIntOrNull() ?: return null
            val m = hhmm.substring(2, 4).toIntOrNull() ?: return null
            if (h > 23 || m > 59) return null
            return LocalTime.of(h, m)
        }
    }
}
