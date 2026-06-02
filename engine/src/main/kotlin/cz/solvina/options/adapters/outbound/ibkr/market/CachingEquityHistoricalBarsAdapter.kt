package cz.solvina.options.adapters.outbound.ibkr.market

import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.EquityHistoricalBarsPort
import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

@Primary
@Component
class CachingEquityHistoricalBarsAdapter(
    private val ibkr: IbkrEquityHistoricalBarsAdapter,
    private val barStorePort: BarStorePort,
) : EquityHistoricalBarsPort {

    override suspend fun fetch5MinBars(symbol: Symbol, days: Int): List<FiveMinuteBar> {
        val now = Instant.now()
        val from = now.minus(days.toLong(), ChronoUnit.DAYS)

        val lastBarTime = barStorePort.lastBarTime(symbol)

        if (lastBarTime != null && lastBarTime.isAfter(now.minus(15, ChronoUnit.MINUTES))) {
            logger.debug { "[${symbol.value}] Historical bars served from InfluxDB cache (last bar: $lastBarTime)" }
            return barStorePort.readBars(symbol, from, now)
        }

        // Fetch only the missing window from IBKR
        val fetchDays = if (lastBarTime != null) {
            (ChronoUnit.HOURS.between(lastBarTime, now) / 24 + 1).toInt().coerceIn(1, days)
        } else {
            days
        }
        logger.info { "[${symbol.value}] Fetching $fetchDays day(s) of historical bars from IBKR (cache last: $lastBarTime)" }

        val ibkrBars = runCatching { ibkr.fetch5MinBars(symbol, fetchDays) }
            .getOrElse { e ->
                logger.warn { "[${symbol.value}] IBKR historical fetch failed: ${e.message} — falling back to InfluxDB cache" }
                emptyList()
            }

        if (ibkrBars.isNotEmpty()) {
            barStorePort.writeBars(symbol, ibkrBars)
            logger.info { "[${symbol.value}] Wrote ${ibkrBars.size} bars to InfluxDB" }
        }

        val cached = barStorePort.readBars(symbol, from, now)
        return cached.ifEmpty {
            logger.warn { "[${symbol.value}] InfluxDB returned no bars after write — using IBKR result directly" }
            ibkrBars
        }
    }

    override suspend fun fetch5MinBarsForRange(symbol: Symbol, from: LocalDate, to: LocalDate): List<FiveMinuteBar> {
        logger.info { "[${symbol.value}] Fetching range $from..$to from IBKR" }
        val bars = runCatching { ibkr.fetch5MinBarsForRange(symbol, from, to) }
            .getOrElse { e ->
                logger.warn { "[${symbol.value}] IBKR range fetch failed: ${e.message}" }
                emptyList()
            }
        if (bars.isNotEmpty()) {
            barStorePort.writeBars(symbol, bars)
            logger.info { "[${symbol.value}] Wrote ${bars.size} bars to InfluxDB for range $from..$to" }
        }
        return bars
    }
}
