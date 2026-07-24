package cz.solvina.options.flags

import cz.solvina.options.domain.features.backtest.BacktestAccountView
import cz.solvina.options.domain.features.backtest.BacktestSignal
import cz.solvina.options.domain.features.backtest.FlagBacktestStrategy
import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.features.flag.config.FlagStrategyConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.models.Symbol
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Money-management levers for the flag backtest (ported to parity with the stock param strategy):
 *   - riskPerTradePct  — dollar risk = current equity × pct/100 (compounds, and shrinks in drawdown)
 *   - account-aware size + affordability cap — a drained account opens NO trade (no bounce-back)
 *   - stopAtrPct / targetAtrPct — exits as % of ATR (150 = 1.5×ATR)
 *   - atrPeriod — ATR lookback override (default 14)
 *
 * The bar sequence reproduces PatternDetectorTest's breakout (pole → flag → close above resistance),
 * re-stamped inside US RTH so session/entry-window filters pass and only the sizing/exit maths is
 * under test.
 */
class FlagBacktestStrategySizingTest {
    private val symbol = Symbol("TEST")
    private val et = ZoneId.of("America/New_York")

    // Compact periods (same as PatternDetectorTest) so a valid pattern forms in 10 bars.
    private val config =
        FlagStrategyConfig(
            atrPeriod = 3,
            atrMultiplier = 2.0,
            volumeMaPeriod = 5,
            volumeSpikeMultiplier = 1.5,
            poleMinBars = 2,
            poleMaxBars = 10,
            flagMinBars = 3,
            flagMaxBars = 15,
            maxRetracementPct = 0.50,
        )
    private val trading = FlagTradingConfig(maxOpenPositions = 3, entryBlockMinutesBeforeClose = 0)

    private fun breakoutBars(): List<FiveMinuteBar> {
        // 2024-01-03 (Wed) 10:00 ET onward — mid-RTH, past skip-first, well before the close.
        val start =
            LocalDate
                .of(2024, 1, 3)
                .atTime(10, 0)
                .atZone(et)
                .toInstant()

        fun t(i: Int) = start.plusSeconds(300L * i)
        return listOf(
            FiveMinuteBar(t(0), 100.0, 101.0, 100.0, 100.0, 1_000),
            FiveMinuteBar(t(1), 100.0, 101.0, 100.0, 100.0, 1_000),
            FiveMinuteBar(t(2), 100.0, 101.0, 100.0, 100.0, 1_000),
            FiveMinuteBar(t(3), 100.0, 101.0, 100.0, 100.0, 1_000),
            FiveMinuteBar(t(4), 100.0, 103.0, 100.0, 103.0, 3_000),
            FiveMinuteBar(t(5), 103.0, 107.0, 103.0, 107.0, 3_000),
            FiveMinuteBar(t(6), 107.0, 107.5, 106.0, 106.5, 1_000),
            FiveMinuteBar(t(7), 106.5, 107.0, 106.0, 106.2, 1_000),
            FiveMinuteBar(t(8), 106.2, 106.8, 105.8, 106.0, 1_000),
            FiveMinuteBar(t(9), 106.0, 108.5, 105.9, 108.0, 1_000), // breakout bar
        )
    }

    /** Warms the detector with the pre-breakout bars, then feeds the breakout bar under [capital]. */
    private fun bracketFor(
        strategy: FlagBacktestStrategy,
        capital: BigDecimal,
    ): BacktestSignal.OpenBracket? {
        val bars = breakoutBars()
        strategy.initialize(listOf(symbol), mapOf(symbol to bars.dropLast(1)))
        return strategy
            .onBar(symbol, bars.last(), BacktestAccountView(capital, 0, 0))
            .filterIsInstance<BacktestSignal.OpenBracket>()
            .firstOrNull()
    }

    private fun strategy(
        stopAtrPct: Double? = null,
        targetAtrPct: Double? = null,
        riskPerTradePct: Double? = 1.0,
        atrPeriod: Int? = null,
    ) = FlagBacktestStrategy(
        config,
        trading,
        stopAtrPct = stopAtrPct,
        targetAtrPct = targetAtrPct,
        riskPerTradePct = riskPerTradePct,
        atrPeriod = atrPeriod,
    )

    @Test
    fun `percent-of-equity risk opens a trade on healthy capital`() {
        val bracket = bracketFor(strategy(), BigDecimal("20000"))
        assertTrue(bracket != null && bracket.shares > 0, "A 1% risk on 20k equity must size a real position")
        assertTrue(bracket!!.stopLossPrice < bracket.entryPrice && bracket.profitTargetPrice > bracket.entryPrice)
    }

    @Test
    fun `drained equity opens no trade (no bounce-back from thin air)`() {
        val bracket = bracketFor(strategy(), BigDecimal("1.00"))
        assertEquals(null, bracket, "A drained account must not open trades — the affordability floor forces 0 shares")
    }

    @Test
    fun `percent risk scales position size with equity`() {
        val small = bracketFor(strategy(), BigDecimal("20000"))!!.shares
        val big = bracketFor(strategy(), BigDecimal("40000"))!!.shares
        assertTrue(big > small, "Doubling equity must increase size under percent-of-equity risk (was $small → $big)")
    }

    @Test
    fun `atr-percent stop widens with the percentage`() {
        val tight = bracketFor(strategy(stopAtrPct = 50.0), BigDecimal("20000"))!!
        val wide = bracketFor(strategy(stopAtrPct = 300.0), BigDecimal("20000"))!!
        assertTrue(wide.stopLossPrice < tight.stopLossPrice, "150→300% of ATR must place the stop further below entry")
    }

    @Test
    fun `atr-percent target sits above entry and widens with the percentage`() {
        val near = bracketFor(strategy(targetAtrPct = 100.0), BigDecimal("20000"))!!
        val far = bracketFor(strategy(targetAtrPct = 400.0), BigDecimal("20000"))!!
        assertTrue(near.profitTargetPrice > near.entryPrice)
        assertTrue(far.profitTargetPrice > near.profitTargetPrice, "A larger % of ATR must place the target further above entry")
    }

    @Test
    fun `atrPeriod override changes the atr-based stop`() {
        val p3 = bracketFor(strategy(stopAtrPct = 150.0, atrPeriod = 3), BigDecimal("20000"))!!
        val p7 = bracketFor(strategy(stopAtrPct = 150.0, atrPeriod = 7), BigDecimal("20000"))!!
        assertTrue(p3.stopLossPrice.compareTo(p7.stopLossPrice) != 0, "Changing the ATR period must change the ATR-based stop")
    }
}
