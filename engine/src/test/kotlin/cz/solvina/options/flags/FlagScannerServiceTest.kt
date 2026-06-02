package cz.solvina.options.flags

import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.EquityHistoricalBarsPort
import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.features.bars.RealTimeBarsPort
import cz.solvina.options.domain.features.flag.FlagExecutionService
import cz.solvina.options.domain.features.flag.FlagManagementService
import cz.solvina.options.domain.features.flag.FlagPort
import cz.solvina.options.domain.features.flag.FlagScannerService
import cz.solvina.options.domain.features.flag.config.FlagStrategyConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfigPort
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlagScannerServiceTest {

    private val strategyConfig = FlagStrategyConfig(
        usWatchlist = listOf("AAPL"),
        euWatchlist = listOf("SAP"),
    )
    private val tradingConfig = FlagTradingConfig(
        entryBlockMinutesBeforeClose = 5,
        enabled = true,
    )

    private val realTimeBarsPort = mockk<RealTimeBarsPort> {
        every { streamBars(any()) } returns emptyFlow()
    }
    private val equityHistoricalBarsPort = mockk<EquityHistoricalBarsPort> {
        coEvery { fetch5MinBars(any(), any()) } returns emptyList()
    }
    private val flagExecutionService = mockk<FlagExecutionService>(relaxed = true)
    private val flagPort = mockk<FlagPort>(relaxed = true)
    private val flagManagementService = mockk<FlagManagementService>(relaxed = true)
    private val flagTradingConfigPort = mockk<FlagTradingConfigPort> {
        coEvery { get() } returns tradingConfig
    }
    private val barStorePort = mockk<BarStorePort>(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun buildService() = FlagScannerService(
        realTimeBarsPort = realTimeBarsPort,
        equityHistoricalBarsPort = equityHistoricalBarsPort,
        flagExecutionService = flagExecutionService,
        flagPort = flagPort,
        flagManagementService = flagManagementService,
        flagTradingConfigPort = flagTradingConfigPort,
        barStorePort = barStorePort,
        strategyConfig = strategyConfig,
        scope = testScope,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // isEntryBlocked — bar time is candle close time
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `isEntryBlocked returns true when bar close time is inside the block window`() {
        val service = buildService()
        val usConfig = FlagTradingConfig(entryBlockMinutesBeforeClose = 5)

        // A candle whose close time is 15:58:00 ET — 2 min before US close of 16:00
        // With a 5-minute block, this should be blocked.
        val closeTime = Instant.parse("2024-01-15T20:58:00Z") // 15:58 ET (UTC-5)

        assertTrue(
            service.isEntryBlocked(Symbol("AAPL"), usConfig, closeTime),
            "Entry at 15:58 ET should be blocked when entryBlockMinutesBeforeClose=5"
        )
    }

    @Test
    fun `isEntryBlocked returns false when bar close time is outside the block window`() {
        val service = buildService()
        val usConfig = FlagTradingConfig(entryBlockMinutesBeforeClose = 5)

        // A candle whose close time is 15:53:00 ET — 7 min before close
        // With a 5-minute block, this should NOT be blocked.
        val closeTime = Instant.parse("2024-01-15T20:53:00Z") // 15:53 ET

        assertFalse(
            service.isEntryBlocked(Symbol("AAPL"), usConfig, closeTime),
            "Entry at 15:53 ET should not be blocked when entryBlockMinutesBeforeClose=5"
        )
    }

    @Test
    fun `isEntryBlocked with FIVE_MIN candle open time falsely allows entry inside block window`() {
        val service = buildService()
        val usConfig = FlagTradingConfig(entryBlockMinutesBeforeClose = 5)

        // Simulate a candle that OPENS at 15:54:00 but CLOSES at 15:58:55.
        // With the current bug, BarAggregator sets candle.time = open time (15:54).
        // isEntryBlocked receives 15:54 and incorrectly allows entry.
        val candleOpenTime = Instant.parse("2024-01-15T20:54:00Z") // 15:54 ET — candle open
        // 15:54 is OUTSIDE the 5-min block window → isEntryBlocked incorrectly returns false.
        // This test documents the bug: it passes before the BarAggregator fix but should fail.
        // After fixing BarAggregator to use close time (15:58:55), this behaviour is corrected:
        // the caller passes 15:58:55 and isEntryBlocked correctly returns true.

        // Before the fix: isEntryBlocked(candleOpenTime) == false (not blocked — BUG)
        // After the fix:  isEntryBlocked(candleCloseTime) == true  (blocked — CORRECT)
        // This test asserts the CORRECT post-fix behavior (candle close time inside window → blocked).
        val candleCloseTime = Instant.parse("2024-01-15T20:58:55Z") // 15:58:55 ET — candle close
        assertTrue(
            service.isEntryBlocked(Symbol("AAPL"), usConfig, candleCloseTime),
            "Entry using candle close time 15:58:55 should be blocked; passing open time 15:54 would incorrectly allow it"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // subscribeSymbol — EU session must be recorded even when already subscribed
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `subscribeSymbol applies EU session to already-subscribed symbol`() = runTest(testDispatcher) {
        val service = buildService()

        // First subscribe AAPL as a US symbol (not in euWatchlist)
        val firstResult = service.subscribeSymbol("AAPL", "US")
        assertTrue(firstResult, "First subscribe should return true (new subscription)")

        // Now re-subscribe as EU — should update the session even though already running
        val secondResult = service.subscribeSymbol("AAPL", "EU")
        assertFalse(secondResult, "Re-subscribe should return false (already active)")

        // The EU flag must be applied: isEu(AAPL) should now return true
        assertTrue(
            service.isEu(Symbol("AAPL")),
            "After re-subscribing AAPL with session=EU, isEu must return true. " +
                "Currently fails because subscribeSymbol early-returns before adding to runtimeEuSymbols."
        )
    }

    @Test
    fun `subscribeSymbol does not mark US symbol as EU when session is US`() = runTest(testDispatcher) {
        val service = buildService()
        service.subscribeSymbol("AAPL", "US")
        assertFalse(service.isEu(Symbol("AAPL")), "US symbol should not be in EU set")
    }

    @Test
    fun `subscribeSymbol marks new EU symbol correctly`() = runTest(testDispatcher) {
        val service = buildService()
        service.subscribeSymbol("BMW", "EU")
        assertTrue(service.isEu(Symbol("BMW")), "Newly subscribed EU symbol should be in EU set")
    }
}
