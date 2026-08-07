package cz.solvina.options.domain.features.bars

import cz.solvina.options.domain.features.strategy.assignment.StrategyAssignmentPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

/**
 * Keeps the bar store current for the symbols the live stock strategies actually trade.
 *
 * Until this existed, bars were only ever written on demand — by a backtest, a sweep or the
 * Historical Data page. Nothing refreshed them on a schedule, so the paper host's newest daily bars
 * were 2.5 weeks old while [cz.solvina.options.domain.features.strategy.live.StockStrategyRunner]
 * was expected to decide off "the latest closed bar". The runner now refuses a stale bar outright;
 * this service is what keeps it from having to.
 *
 * Scope is deliberately the enabled assignments, not the whole universe: the universe is ~200
 * symbols, each one is an IBKR historical request, and requesting bars nothing trades on would
 * spend the pacing budget that the strategies need.
 *
 * ### Why a re-fetch rather than [HistoricalDataService.ensureCoverage]
 * `ensureCoverage` only fills gaps of at least three consecutive trading days, so that a market
 * holiday is never re-requested. A nightly top-up is the exact opposite shape — one or two missing
 * days at the tail — and would be skipped by that rule every time. So this asks for a short window
 * outright and lets the store overwrite what it already has.
 */
@Service
class DailyBarRefreshService(
    private val assignments: StrategyAssignmentPort,
    private val equityHistoricalBars: EquityHistoricalBarsPort,
    private val clock: Clock,
    /**
     * Days of history requested per symbol.
     *
     * Wide on purpose, and it costs nothing: a daily series fits ten years in one IBKR request
     * ([Timeframe.maxChunkDays]), so 45 days and 5 days are the same single call. What the width
     * buys is gap repair — after an engine outage, a holiday cluster or a run that never happened,
     * a narrow tail refresh would append recent bars and leave a hole behind them, and an indicator
     * computed across a hole is wrong without being visibly wrong.
     */
    @param:Value("\${stock-strategies.bar-refresh.lookback-days:45}") private val lookbackDays: Long = 45,
    /**
     * Pause between symbols. IBKR allows roughly 60 historical requests per 10 minutes and answers
     * a violation with a silently dropped response (error 162/420), which surfaces here as a 45s
     * chunk timeout per symbol — far more expensive than waiting. 10s ≈ 6 requests/minute keeps a
     * 113-symbol pass inside 20 minutes and well under the limit.
     */
    @param:Value("\${stock-strategies.bar-refresh.pacing-ms:10000}") private val pacingMs: Long = 10_000,
) {
    data class Result(
        val symbols: Int,
        val barsWritten: Int,
        val failures: List<String>,
    )

    /**
     * One pass over every (symbol, timeframe) an enabled assignment names.
     *
     * Sequential and paced by construction. A failure on one symbol is logged and the pass
     * continues: a delisted or mistyped symbol must not cost every symbol behind it its refresh.
     */
    suspend fun refreshOnce(): Result {
        val targets =
            assignments
                .findEnabled()
                .map { it.symbol to it.timeframe }
                .distinct()
        if (targets.isEmpty()) {
            logger.debug { "Daily bar refresh: no enabled assignments — nothing to refresh" }
            return Result(0, 0, emptyList())
        }

        val today = LocalDate.now(clock.withZone(REQUEST_ZONE))
        val from = today.minusDays(lookbackDays)
        logger.info { "Daily bar refresh: ${targets.size} (symbol, timeframe) pair(s), window $from..$today" }

        var barsWritten = 0
        val failures = mutableListOf<String>()
        targets.forEachIndexed { index, (symbol, timeframe) ->
            if (index > 0) delay(pacingMs)
            runCatching { equityHistoricalBars.fetch5MinBarsForRange(symbol, from, today, timeframe) }
                .onSuccess { bars ->
                    barsWritten += bars.size
                    logger.debug { "[${symbol.value}] refreshed ${bars.size} ${timeframe.label} bar(s)" }
                }.onFailure { e ->
                    logger.warn(e) { "[${symbol.value}] ${timeframe.label} refresh failed: ${e.message}" }
                    failures += "${symbol.value} ${timeframe.label}: ${e.message}"
                }
        }
        logger.info { "Daily bar refresh complete: $barsWritten bar(s) over ${targets.size} pair(s), ${failures.size} failure(s)" }
        return Result(targets.size, barsWritten, failures)
    }

    /** Symbols this service is responsible for — the audit reports freshness against exactly these. */
    fun trackedSymbols(): List<Symbol> = assignments.findEnabled().map { it.symbol }.distinct()

    private companion object {
        /**
         * The request window is expressed in dates, and IBKR resolves them against the contract's
         * own exchange. Using the engine's timezone would move the window by a day either side of
         * midnight for no benefit; UTC keeps it stable.
         */
        val REQUEST_ZONE: ZoneId = ZoneId.of("UTC")
    }
}
