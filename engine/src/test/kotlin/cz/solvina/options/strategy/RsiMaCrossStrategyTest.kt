package cz.solvina.options.strategy

import cz.solvina.options.domain.features.bars.Candle
import cz.solvina.options.domain.features.bars.RsiCalculator
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.regime.classifyRegime
import cz.solvina.options.domain.features.strategy.Decision
import cz.solvina.options.domain.features.strategy.RsiMaCrossStrategy
import cz.solvina.options.domain.features.strategy.StrategyContext
import cz.solvina.options.domain.models.Symbol
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RsiMaCrossStrategyTest {
    private val symbol = Symbol("TEST")

    private fun candle(
        i: Int,
        close: Double,
    ) = Candle(
        time = Instant.parse("2020-01-01T00:00:00Z").plusSeconds(i * 86_400L),
        open = close,
        high = close * 1.01,
        low = close * 0.99,
        close = close,
        volume = 1_000,
    )

    /** Feeds a close series bar by bar and returns the bar index of every entry decision. */
    private fun entriesOn(
        closes: List<Double>,
        strategy: RsiMaCrossStrategy,
    ): List<Int> {
        val hits = mutableListOf<Int>()
        closes.forEachIndexed { i, c ->
            val d: Decision? =
                strategy.decide(
                    StrategyContext(
                        symbol = symbol,
                        candle = candle(i, c),
                        equity = BigDecimal("20000"),
                        openPositions = 0,
                        pendingPositions = 0,
                    ),
                )
            if (d != null) hits += i
        }
        return hits
    }

    /** Falls for 40 bars, then rises for 40 — RSI crosses its MA once, on the turn. */
    private fun vShape(): List<Double> = (0 until 40).map { 100.0 - it } + (0 until 40).map { 61.0 + it }

    @Test
    fun `enters on the cross, not on every bar above the line`() {
        val strategy = RsiMaCrossStrategy(RsiMaCrossStrategy.Params(rsiPeriod = 5, rsiMaPeriod = 5, maxOpenPositions = 99))

        val entries = entriesOn(vShape(), strategy)

        // The turn produces exactly one entry. A level test ("RSI above its MA") would fire on
        // nearly every bar of the 40-bar rally instead.
        assertEquals(1, entries.size, "expected a single cross entry, got bars $entries")
        assertTrue(entries.single() in 41..46, "cross should land just after the turn, was ${entries.single()}")
    }

    @Test
    fun `the oversold gate suppresses a cross that happens too high`() {
        val closes = vShape()
        val ungated = RsiMaCrossStrategy(RsiMaCrossStrategy.Params(rsiPeriod = 5, rsiMaPeriod = 5, maxOpenPositions = 99))
        val gated =
            RsiMaCrossStrategy(
                RsiMaCrossStrategy.Params(
                    rsiPeriod = 5,
                    rsiMaPeriod = 5,
                    requireOversold = true,
                    rsiOversold = 1.0, // unreachably strict
                    maxOpenPositions = 99,
                ),
            )

        assertEquals(1, entriesOn(closes, ungated).size)
        assertTrue(entriesOn(closes, gated).isEmpty(), "an unreachable oversold gate must block every cross")
    }

    @Test
    fun `no decision before the RSI MA has enough history`() {
        val strategy = RsiMaCrossStrategy(RsiMaCrossStrategy.Params(rsiPeriod = 14, rsiMaPeriod = 14))
        // RSI needs 15 closes, its MA another 14 — nothing may fire inside that window.
        val closes = (0 until 20).map { 100.0 + sin(it / 3.0) }

        assertTrue(entriesOn(closes, strategy).isEmpty())
    }

    @Test
    fun `the position cap is respected`() {
        val strategy = RsiMaCrossStrategy(RsiMaCrossStrategy.Params(rsiPeriod = 5, rsiMaPeriod = 5, maxOpenPositions = 1))
        val closes = vShape()

        val withExposure =
            closes.mapIndexedNotNull { i, c ->
                strategy.decide(
                    StrategyContext(
                        symbol = symbol,
                        candle = candle(i, c),
                        equity = BigDecimal("20000"),
                        openPositions = 1, // already at the cap
                        pendingPositions = 0,
                    ),
                )
            }

        assertTrue(withExposure.isEmpty())
    }

    @Test
    fun `a decision carries a stop below and a target above the entry`() {
        val strategy =
            RsiMaCrossStrategy(RsiMaCrossStrategy.Params(rsiPeriod = 5, rsiMaPeriod = 5, stopLossPct = 4.0, targetPct = 10.0))
        val closes = vShape()

        var decision: Decision? = null
        closes.forEachIndexed { i, c ->
            if (decision == null) {
                decision =
                    strategy.decide(
                        StrategyContext(
                            symbol = symbol,
                            candle = candle(i, c),
                            equity = BigDecimal("20000"),
                            openPositions = 0,
                            pendingPositions = 0,
                        ),
                    )
            }
        }

        val d = assertNotNull(decision)
        assertTrue(d.stopLossPrice < d.entryPrice)
        assertTrue(d.profitTargetPrice > d.entryPrice)
        assertTrue(d.shares > 0)
    }

    @Test
    fun `warmup seeds state so the same series decides the same either way`() {
        val closes = vShape()
        val params = RsiMaCrossStrategy.Params(rsiPeriod = 5, rsiMaPeriod = 5, maxOpenPositions = 99)

        val streamed = entriesOn(closes, RsiMaCrossStrategy(params))

        // Same data, but the first 41 bars arrive through warmup instead of decide: the entries that
        // remain in the live window must be identical, which is what makes a warm-up honest.
        val warmed = RsiMaCrossStrategy(params)
        warmed.warmup(symbol, mapOf(Timeframe.DAILY to closes.take(41).mapIndexed { i, c -> candle(i, c) }))
        val afterWarmup =
            closes.drop(41).mapIndexedNotNull { i, c ->
                warmed
                    .decide(
                        StrategyContext(
                            symbol = symbol,
                            candle = candle(i + 41, c),
                            equity = BigDecimal("20000"),
                            openPositions = 0,
                            pendingPositions = 0,
                        ),
                    )?.let { i + 41 }
            }

        assertEquals(streamed.filter { it >= 41 }, afterWarmup)
    }

    @Test
    fun `the regime service and the strategies share one RSI implementation`() {
        val closes = (0 until 60).map { 100.0 + 10.0 * sin(it / 4.0) }

        val direct = RsiCalculator.last(closes, 14)
        val viaRegime = classifyRegime(closes.map { BigDecimal(it) }, 20, 50, 14).rsi

        assertNotNull(direct)
        assertNotNull(viaRegime)
        assertEquals(BigDecimal(direct).setScale(2, java.math.RoundingMode.HALF_UP), viaRegime)
    }

    @Test
    fun `a dead-flat series is neutral, not maximally overbought`() {
        assertEquals(50.0, RsiCalculator.last(List(40) { 100.0 }, 14))
        assertEquals(100.0, RsiCalculator.last((1..40).map { it.toDouble() }, 14))
        assertNull(RsiCalculator.last(List(5) { 100.0 }, 14))
    }
}
