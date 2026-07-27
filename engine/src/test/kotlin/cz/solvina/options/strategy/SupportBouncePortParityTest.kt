package cz.solvina.options.strategy

import cz.solvina.options.domain.features.backtest.BacktestEngine
import cz.solvina.options.domain.features.backtest.StrategyBacktestAdapter
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.Candle
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.strategy.StrategyTrade
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port parity: moving the support-bounce rules from `RuleBacktestStrategy` onto the host-neutral
 * [cz.solvina.options.domain.features.strategy.StockStrategy] seam must not change a single trade.
 *
 * Runs the verbatim pre-seam implementation ([LegacyRuleStrategy]) and the ported one through the
 * same [BacktestEngine] over the same deterministic series, and compares the resulting trades
 * field by field. A refactor that silently shifts an indicator by one bar would show up here as a
 * different entry price or a missing trade, which is exactly the failure a "pure refactor" claim
 * has to rule out.
 */
class SupportBouncePortParityTest {
    private val symbol = Symbol("TEST")
    private val ny = ZoneId.of("America/New_York")

    /**
     * Deterministic pseudo-market: a slow sine trend plus seeded noise, so the series trends,
     * pulls back and recovers often enough to trip a support-bounce rule many times.
     */
    private fun series(days: Int): List<Candle> {
        val rnd = Random(20260727)
        var close = 100.0
        val out = mutableListOf<Candle>()
        var date = LocalDate.of(2015, 1, 1)
        repeat(days) { i ->
            val drift = sin(i / 40.0) * 0.6
            val noise = (rnd.nextDouble() - 0.5) * 2.2
            val open = close
            close = (close + drift + noise).coerceAtLeast(5.0)
            val high = maxOf(open, close) + abs(noise) * 0.5
            val low = minOf(open, close) - abs(noise) * 0.5
            out +=
                Candle(
                    time = date.atTime(16, 0).atZone(ny).toInstant(),
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = 1_000L + i,
                )
            date = date.plusDays(1)
        }
        return out
    }

    private val params =
        LegacyRuleStrategy.Params(
            rsiPeriod = 14,
            rsiOversold = 100.0,
            requireRsiRising = false,
            smaFastPeriod = 20,
            smaSlowPeriod = 50,
            requireUptrend = false,
            supportProximityPct = 50.0,
            stopLossPct = 3.0,
            targetPct = 6.0,
            riskPerTrade = 200.0,
            maxOpenPositions = 5,
        )

    private fun ported() = SupportBounceStrategyParams.toPorted(params)

    private fun store(bars: List<Candle>): BarStorePort =
        mockk<BarStorePort>().also {
            coEvery { it.readBars(any(), any(), any(), any()) } returns bars
        }

    @Test
    fun `ported strategy reproduces the legacy implementation trade for trade`() =
        runTest {
            val bars = series(days = 1200)
            val request =
                BacktestEngine.Request(
                    symbols = listOf(symbol),
                    from = LocalDate.of(2015, 3, 1),
                    to = LocalDate.of(2018, 4, 1),
                    initialCapital = BigDecimal("20000"),
                    timeframe = Timeframe.DAILY,
                    holdOvernight = true,
                )

            val legacy =
                BacktestEngine(store(bars)).run<LegacyRuleStrategy.RuleTrade>(request, LegacyRuleStrategy(params))
            val ported =
                BacktestEngine(store(bars)).run<StrategyTrade>(request, StrategyBacktestAdapter(ported()))

            assertTrue(legacy.trades.isNotEmpty(), "baseline produced no trades — the fixture proves nothing")
            assertEquals(legacy.trades.size, ported.trades.size, "trade count differs")
            legacy.trades.forEachIndexed { i, l ->
                val p = ported.trades[i]
                assertEquals(l.symbol, p.symbol, "trade $i symbol")
                assertEquals(l.entryAt, p.entryAt, "trade $i entryAt")
                assertEquals(l.entryPrice, p.entryPrice, "trade $i entryPrice")
                assertEquals(l.exitAt, p.exitAt, "trade $i exitAt")
                assertEquals(l.exitPrice, p.exitPrice, "trade $i exitPrice")
                assertEquals(l.closeReason, p.closeReason, "trade $i closeReason")
                assertEquals(l.shares, p.shares, "trade $i shares")
                assertEquals(l.pnl, p.pnl, "trade $i pnl")
            }
            assertEquals(legacy.summary.totalPnl, ported.summary.totalPnl, "totalPnl")
            assertEquals(legacy.summary.finalCapital, ported.summary.finalCapital, "finalCapital")
            assertEquals(legacy.summary.winCount, ported.summary.winCount, "winCount")
            assertEquals(legacy.summary.lossCount, ported.summary.lossCount, "lossCount")
            assertEquals(legacy.summary.maxDrawdownPct, ported.summary.maxDrawdownPct, "maxDrawdownPct")
        }
}

/** Field-by-field bridge between the legacy params and the ported ones, so the test tunes one set. */
object SupportBounceStrategyParams {
    fun toPorted(p: LegacyRuleStrategy.Params) =
        cz.solvina.options.domain.features.strategy.SupportBounceStrategy(
            cz.solvina.options.domain.features.strategy.SupportBounceStrategy.Params(
                rsiPeriod = p.rsiPeriod,
                rsiOversold = p.rsiOversold,
                requireRsiRising = p.requireRsiRising,
                smaFastPeriod = p.smaFastPeriod,
                smaSlowPeriod = p.smaSlowPeriod,
                requireUptrend = p.requireUptrend,
                supportProximityPct = p.supportProximityPct,
                stopLossPct = p.stopLossPct,
                targetPct = p.targetPct,
                atrPeriod = p.atrPeriod,
                stopAtrMultiple = p.stopAtrMultiple,
                targetAtrMultiple = p.targetAtrMultiple,
                riskPerTrade = p.riskPerTrade,
                riskPerTradePct = p.riskPerTradePct,
                maxOpenPositions = p.maxOpenPositions,
                maxLeverage = p.maxLeverage,
            ),
        )
}
