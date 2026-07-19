package cz.solvina.options.adapters.outbound.backtest

import cz.solvina.options.domain.features.bars.EquityHistoricalBarsPort
import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * Backtest-profile stand-in for the IBKR historical fetch chain. The dedicated backtest instance
 * runs off a local InfluxDB replica synced from the RPi master and must never wait on IBKR, so
 * ensureCoverage gaps complete instantly with zero bars instead of stalling 45s per silent chunk.
 * The warn is the operator's cue to run scripts/sync-influx-from-rpi.py (or extend the fetch range
 * on the master first).
 */
@Profile("backtest")
@Primary
@Component
class NoopEquityHistoricalBarsAdapter : EquityHistoricalBarsPort {
    override suspend fun fetch5MinBars(
        symbol: Symbol,
        days: Int,
    ): List<FiveMinuteBar> {
        logger.warn { "[${symbol.value}] backtest profile: live 5min fetch requested but IBKR is offline here — returning empty" }
        return emptyList()
    }

    override suspend fun fetch5MinBarsForRange(
        symbol: Symbol,
        from: LocalDate,
        to: LocalDate,
        timeframe: Timeframe,
        onChunk: suspend (List<FiveMinuteBar>) -> Unit,
    ): List<FiveMinuteBar> {
        logger.warn {
            "[${symbol.value}] backtest profile: ${timeframe.label} $from..$to not in local InfluxDB and IBKR is offline here — " +
                "sync it from the RPi master (scripts/sync-influx-from-rpi.py)"
        }
        return emptyList()
    }
}
