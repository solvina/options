package cz.solvina.options.domain.features.backtest.sweep

import cz.solvina.options.domain.features.backtest.RuleBacktestStrategy
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.HistoricalDataService
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals

class SweepServiceTest {
    private val service =
        SweepService(
            barStore = mockk<BarStorePort>(relaxed = true),
            historicalData = mockk<HistoricalDataService>(relaxed = true),
            outputDirProp = "build/tmp/sweep-test",
        )

    private fun def(axes: Map<String, SweepAxis>): SweepDefinition =
        SweepDefinition(
            name = "t",
            symbols = listOf(Symbol("AAPL")),
            from = LocalDate.parse("2020-01-01"),
            to = LocalDate.parse("2025-01-01"),
            timeframe = Timeframe.DAILY,
            initialCapital = BigDecimal("20000"),
            baseParams = RuleBacktestStrategy.Params(),
            axes = LinkedHashMap(axes),
            parallelism = 1,
        )

    private fun bd(vararg v: String) = SweepAxis(v.map { BigDecimal(it) })

    private fun range(
        min: String,
        max: String,
        step: String,
    ) = SweepAxis.range(BigDecimal(min), BigDecimal(max), BigDecimal(step))

    @Test
    fun `range expansion is decimal-exact and inclusive`() {
        assertEquals(
            listOf("2", "2.1", "2.2", "2.3"),
            range("2", "2.3", "0.1").values.map { SweepService.cell(it) },
        )
    }

    @Test
    fun `count and stream agree on the mixed zero-and-positive ATR grid`() {
        // 3 x 2 x 2 x 2 = 24 raw; survivors: (0,0)->6, (2,0)->2, (0,3)->3, (2,3)->1 = 12
        val d =
            def(
                linkedMapOf(
                    "stopLossPct" to bd("3", "4", "5"),
                    "targetPct" to bd("6", "8"),
                    "stopAtrMultiple" to bd("0", "2"),
                    "targetAtrMultiple" to bd("0", "3"),
                ),
            )
        val counts = service.counts(d)
        assertEquals(24, counts.total)
        assertEquals(12, counts.toRun)
        assertEquals(12, service.comboSequence(d).count())
    }

    @Test
    fun `the 290k grid collapses to 330 like the python sweeper`() {
        val d =
            def(
                linkedMapOf(
                    "stopLossPct" to range("2", "3.5", "0.1"), // 16
                    "targetPct" to range("3", "8.4", "0.1"), // 55
                    "stopAtrMultiple" to range("2", "3", "0.1"), // 11, all > 0
                    "targetAtrMultiple" to range("3", "4.4", "0.1"), // 15, all > 0
                    "maxOpenPositions" to bd("1", "2"), // 2
                ),
            )
        val counts = service.counts(d)
        assertEquals(290_400, counts.total)
        assertEquals(330, counts.toRun)
        assertEquals(330, service.comboSequence(d).count())
    }

    @Test
    fun `fixed positive ATR multiple in base params suppresses the whole percent axis`() {
        val d =
            def(linkedMapOf("stopLossPct" to bd("3", "4", "5"))).let {
                it.copy(baseParams = it.baseParams.copy(stopAtrMultiple = 2.0))
            }
        assertEquals(3, service.counts(d).total)
        assertEquals(1, service.counts(d).toRun)
        assertEquals(1, service.comboSequence(d).count())
    }

    @Test
    fun `boolean axes sweep both branches`() {
        val d = def(linkedMapOf("requireUptrend" to SweepAxis(listOf(true, false)), "stopLossPct" to bd("3", "4")))
        assertEquals(4, service.counts(d).toRun)
        assertEquals(4, service.comboSequence(d).count())
    }

    @Test
    fun `cells format like python str()`() {
        assertEquals("3", SweepService.cell(BigDecimal("3.0")))
        assertEquals("2.6", SweepService.cell(BigDecimal("2.60")))
        assertEquals("true", SweepService.cell(true))
        assertEquals("", SweepService.cell(null))
    }

    @Test
    fun `applyParam converts int and bool fields`() {
        var p = RuleBacktestStrategy.Params()
        p = service.applyParam(p, "smaSlowPeriod", BigDecimal("150"))
        p = service.applyParam(p, "stopAtrMultiple", BigDecimal("2.5"))
        p = service.applyParam(p, "requireUptrend", false)
        assertEquals(150, p.smaSlowPeriod)
        assertEquals(2.5, p.stopAtrMultiple)
        assertEquals(false, p.requireUptrend)
    }
}
