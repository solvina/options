package cz.solvina.options.api

import cz.solvina.options.adapters.inbound.api.ScannerApiImpl
import cz.solvina.options.domain.features.regime.RegimeConfig
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.scanner.BullPutScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.scanner.StrategyParamsRegistry
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/** The Trading Rules page shows exactly what the bound config beans and the universe say — nothing re-typed. */
class SpreadRulesApiTest {
    private val bullPut = BullPutScannerConfig(ivRankThreshold = 45.0, stopLossPercent = 0.0, timeProfitDte = 21)
    private val bearCall = BearCallScannerConfig(enabled = true, minCreditPerShare = BigDecimal("0.90"))
    private val universe = mockk<UniversePort>()

    private fun api() =
        ScannerApiImpl(
            scannerPort = mockk(),
            scannerService = mockk(),
            spreadQuery = mockk(),
            killSwitch = mockk(),
            scannerConfig = ScannerConfig(maxOpenSpreads = 20, maxNewEntriesPerDay = 15),
            strategyParams = StrategyParamsRegistry(listOf(bullPut, bearCall)),
            bearCallConfig = bearCall,
            regimeConfig = RegimeConfig(gatingEnabled = true),
            universePort = universe,
        )

    @Test
    fun `reports effective strategy and portfolio values`() =
        runTest {
            coEvery { universe.getAll() } returns listOf(InstrumentConfig(symbol = Symbol("AAPL")))

            val rules = api().getSpreadRules().body!!

            val bp = rules.strategies.single { it.strategyId == "BULL_PUT" }
            val bc = rules.strategies.single { it.strategyId == "BEAR_CALL" }
            assertEquals(0, BigDecimal("45").compareTo(bp.ivRankThreshold))
            assertEquals(0, BigDecimal.ZERO.compareTo(bp.stopLossPercent))
            assertEquals(21, bp.timeProfitDte)
            assertEquals(true, bc.enabled)
            assertEquals(BigDecimal("0.90"), bc.minCreditPerShare)
            assertEquals(20, rules.portfolio.maxOpenSpreads)
            assertEquals(15, rules.portfolio.maxNewEntriesPerDay)
            assertEquals(true, rules.portfolio.directionalGateEnabled)
            assertEquals(emptyList(), rules.symbolOverrides)
        }

    @Test
    fun `lists only symbols that override a rule`() =
        runTest {
            coEvery { universe.getAll() } returns
                listOf(
                    InstrumentConfig(symbol = Symbol("MSFT")),
                    InstrumentConfig(symbol = Symbol("TSLA"), ivRankThreshold = 60.0, timeProfitDte = 14),
                )

            val overrides = api().getSpreadRules().body!!.symbolOverrides

            assertEquals(1, overrides.size)
            assertEquals("TSLA", overrides.single().symbol)
            assertEquals(mapOf("ivRankThreshold" to "60.0", "timeProfitDte" to "14"), overrides.single().overrides)
        }
}
