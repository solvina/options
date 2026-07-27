package cz.solvina.options.strategy

import cz.solvina.options.adapters.inbound.api.StockBacktestApiController
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.features.strategy.StockStrategy
import cz.solvina.options.domain.features.strategy.StrategyLibraryConfig
import cz.solvina.options.domain.features.strategy.StrategyParams
import cz.solvina.options.domain.features.strategy.StrategyRegistry
import cz.solvina.options.domain.features.strategy.SupportBounceStrategy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

class StrategyRegistryTest {
    private val registry = StrategyRegistry(listOf(StrategyLibraryConfig().supportBounceStrategy()))

    @Test
    fun `registry exposes the library templates by id`() {
        val strategy = registry.require(SupportBounceStrategy.ID)

        assertContains(registry.all().map { it.id }, SupportBounceStrategy.ID)
        assertContains(strategy.params.map { it.name }, "rsiPeriod")
        assertContains(strategy.params.map { it.name }, "smaSlowPeriod")
        assertEquals(listOf(Timeframe.DAILY), strategy.inputs.timeframes)
        assertFalse(strategy.inputs.requiresTicks)
    }

    @Test
    fun `duplicate strategy ids fail at construction, not at query time`() {
        val e =
            assertFailsWith<IllegalArgumentException> {
                StrategyRegistry(listOf(SupportBounceStrategy(), SupportBounceStrategy()))
            }
        assertContains(e.message!!, SupportBounceStrategy.ID)
    }

    @Test
    fun `a params blob resolves to the same strategy the flat legacy fields produce`() {
        val flat =
            StockBacktestApiController.StockBacktestRequest(
                symbols = listOf("AAPL"),
                from = LocalDate.of(2020, 1, 1),
                to = LocalDate.of(2021, 1, 1),
                rsiPeriod = 9,
                stopAtrMultiple = 1.5,
                riskPerTradePct = 2.0,
                maxOpenPositions = 4,
            )
        val template = registry.require(SupportBounceStrategy.ID)

        val fromFlat = StrategyParams.resolve(template.params, flat.flatOverrides())
        val fromBlob =
            StrategyParams.resolve(
                template.params,
                mapOf("rsiPeriod" to 9, "stopAtrMultiple" to 1.5, "riskPerTradePct" to 2.0, "maxOpenPositions" to 4),
            )

        assertEquals(fromFlat.asMap(), fromBlob.asMap())
        // …and both describe the same strategy the legacy typed path built.
        assertEquals(flat.toParams(), SupportBounceStrategy.from(fromFlat))
    }

    @Test
    fun `an unknown param name is rejected rather than silently ignored`() {
        val template = registry.require(SupportBounceStrategy.ID)

        val e = assertFailsWith<IllegalArgumentException> { StrategyParams.resolve(template.params, mapOf("rsiPeroid" to 9)) }
        assertContains(e.message!!, "rsiPeroid")
    }

    @Test
    fun `warmup is counted in bars, so intraday windows do not fetch decades`() {
        val bars = 200

        // Daily: 200 bars ≈ 200 trading days ≈ 400 calendar days (the long-standing behaviour).
        assertEquals(400L, StockBacktestApiController.warmupCalendarDays(bars, Timeframe.DAILY))
        // Intraday: the same 200 bars are days, not years.
        assertEquals(200L, StockBacktestApiController.warmupCalendarDays(bars, Timeframe.FOUR_HOUR))
        assertEquals(30L, StockBacktestApiController.warmupCalendarDays(bars, Timeframe.FIVE_MIN))
    }

    @Test
    fun `a configured instance is independent of the template`() {
        val template: StockStrategy = registry.require(SupportBounceStrategy.ID)
        val params = StrategyParams.resolve(template.params, mapOf("smaSlowPeriod" to 20, "smaFastPeriod" to 10))

        val configured = template.withParams(params, Timeframe.FOUR_HOUR)

        assertNotSame(template, configured)
        assertEquals(listOf(Timeframe.FOUR_HOUR), configured.inputs.timeframes)
        assertEquals(20, configured.inputs.warmupBars)
        // The template keeps its own defaults — one run cannot reconfigure another.
        assertEquals(listOf(Timeframe.DAILY), template.inputs.timeframes)
        assertEquals(200, template.inputs.warmupBars)
    }
}
