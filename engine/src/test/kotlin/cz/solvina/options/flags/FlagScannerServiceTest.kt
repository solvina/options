package cz.solvina.options.flags

import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.EquityHistoricalBarsPort
import cz.solvina.options.domain.features.bars.RealTimeBarsPort
import cz.solvina.options.domain.features.flag.FlagExecutionService
import cz.solvina.options.domain.features.flag.FlagManagementService
import cz.solvina.options.domain.features.flag.FlagPort
import cz.solvina.options.domain.features.flag.FlagScannerService
import cz.solvina.options.domain.features.flag.config.FlagStrategyConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfig
import cz.solvina.options.domain.features.flag.config.FlagTradingConfigPort
import cz.solvina.options.domain.features.universe.MarketSchedule
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Market-hours routing rules for the flag scanner:
 *
 *   — Exchange schedule (timezone, open, close, session label) is owned by UniversePort.
 *   — FlagScannerService must not contain hardcoded timezone strings or exchange hours.
 *   — isEntryBlocked uses the port-provided close time in the port-provided timezone.
 *   — resolveWatchlist (called by onStartup) subscribes only symbols whose market is open
 *     according to the port, not a hardcoded clock comparison.
 */
class FlagScannerServiceTest {
    private val aapl = Symbol("AAPL")
    private val sap = Symbol("SAP")

    private val strategyConfig =
        FlagStrategyConfig(
            usWatchlist = listOf("AAPL"),
            euWatchlist = listOf("SAP"),
        )
    private val tradingConfig =
        FlagTradingConfig(
            entryBlockMinutesBeforeClose = 5,
            enabled = true,
        )

    private val usSchedule =
        MarketSchedule(
            zone = ZoneId.of("America/New_York"),
            open = LocalTime.of(9, 30),
            close = LocalTime.of(16, 0),
            session = "US",
        )
    private val euSchedule =
        MarketSchedule(
            zone = ZoneId.of("Europe/Berlin"),
            open = LocalTime.of(9, 0),
            close = LocalTime.of(17, 30),
            session = "EU",
        )

    private val realTimeBarsPort =
        mockk<RealTimeBarsPort> {
            every { streamBars(any()) } returns emptyFlow()
        }
    private val equityHistoricalBarsPort =
        mockk<EquityHistoricalBarsPort> {
            coEvery { fetch5MinBars(any(), any()) } returns emptyList()
        }
    private val flagExecutionService = mockk<FlagExecutionService>(relaxed = true)
    private val flagPort = mockk<FlagPort>(relaxed = true)
    private val flagManagementService = mockk<FlagManagementService>(relaxed = true)
    private val flagTradingConfigPort =
        mockk<FlagTradingConfigPort> {
            coEvery { get() } returns tradingConfig
        }
    private val barStorePort = mockk<BarStorePort>(relaxed = true)

    // Default universe port: both markets closed, returns US schedule for AAPL and EU for SAP.
    private val defaultUniversePort =
        mockk<UniversePort> {
            every { isMarketOpen(any()) } returns false
            every { getMarketSchedule(aapl) } returns usSchedule
            every { getMarketSchedule(sap) } returns euSchedule
        }

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun buildService(
        universePort: UniversePort = defaultUniversePort,
        clock: Clock = Clock.systemUTC(),
    ) = FlagScannerService(
        realTimeBarsPort = realTimeBarsPort,
        equityHistoricalBarsPort = equityHistoricalBarsPort,
        flagExecutionService = flagExecutionService,
        flagPort = flagPort,
        flagManagementService = flagManagementService,
        flagTradingConfigPort = flagTradingConfigPort,
        barStorePort = barStorePort,
        strategyConfig = strategyConfig,
        connectionStatusPort = mockk(relaxed = true),
        universePort = universePort,
        scope = testScope,
        clock = clock,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // isEntryBlocked — US exchange (AAPL)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `isEntryBlocked returns true when bar close time is inside the US block window`() {
        val service = buildService()
        val config = FlagTradingConfig(entryBlockMinutesBeforeClose = 5)

        // 15:58 ET (UTC-5 in January) = 20:58 UTC — 2 min before US close of 16:00.
        // With a 5-minute block (cutoff 15:55 ET) this should be blocked.
        val barTime = Instant.parse("2024-01-15T20:58:00Z")

        assertTrue(
            service.isEntryBlocked(aapl, config, barTime),
            "Entry at 15:58 ET should be blocked when entryBlockMinutesBeforeClose=5",
        )
    }

    @Test
    fun `isEntryBlocked returns false when bar close time is outside the US block window`() {
        val service = buildService()
        val config = FlagTradingConfig(entryBlockMinutesBeforeClose = 5)

        // 15:53 ET = 20:53 UTC — 7 min before US close. Outside the 5-minute block.
        val barTime = Instant.parse("2024-01-15T20:53:00Z")

        assertFalse(
            service.isEntryBlocked(aapl, config, barTime),
            "Entry at 15:53 ET should not be blocked when entryBlockMinutesBeforeClose=5",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isEntryBlocked — EU exchange (SAP), verifying port-driven schedule is used
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `isEntryBlocked uses EU close time for an EU-configured symbol`() {
        val service = buildService()
        val config = FlagTradingConfig(entryBlockMinutesBeforeClose = 5)

        // January — CET = UTC+1.
        // 17:26 Berlin = 16:26 UTC — 4 min before EU close of 17:30.
        // With a 5-minute block (cutoff 17:25 Berlin) this should be blocked.
        val barTime = Instant.parse("2024-01-15T16:26:00Z")

        assertTrue(
            service.isEntryBlocked(sap, config, barTime),
            "SAP at 17:26 Berlin should be blocked when entryBlockMinutesBeforeClose=5",
        )
    }

    @Test
    fun `isEntryBlocked does not confuse EU and US close times`() {
        // 16:26 UTC in January = 17:26 Berlin (blocked for SAP) but only 11:26 ET (not blocked for AAPL).
        // If hardcoded US hours were mistakenly applied to SAP, it would not be blocked.
        // If hardcoded EU hours were mistakenly applied to AAPL, it would be blocked.
        val service = buildService()
        val config = FlagTradingConfig(entryBlockMinutesBeforeClose = 5)

        val barTime = Instant.parse("2024-01-15T16:26:00Z")

        assertTrue(
            service.isEntryBlocked(sap, config, barTime),
            "SAP must be blocked: 17:26 Berlin is within 5 min of EU close 17:30",
        )
        assertFalse(
            service.isEntryBlocked(aapl, config, barTime),
            "AAPL must not be blocked: 11:26 ET is far from US close 16:00",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isEntryBlocked — candle open time vs close time (documents BarAggregator contract)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `isEntryBlocked is correctly evaluated against candle close time not open time`() {
        val service = buildService()
        val config = FlagTradingConfig(entryBlockMinutesBeforeClose = 5)

        // A candle opens at 15:54 ET (outside block window) but closes at 15:58:55 ET (inside window).
        // The scanner passes bar.time which BarAggregator sets to the candle close.
        val candleCloseTime = Instant.parse("2024-01-15T20:58:55Z") // 15:58:55 ET
        val candleOpenTime = Instant.parse("2024-01-15T20:54:00Z") // 15:54 ET — would not be blocked

        assertTrue(
            service.isEntryBlocked(aapl, config, candleCloseTime),
            "Candle close at 15:58:55 ET should be blocked",
        )
        assertFalse(
            service.isEntryBlocked(aapl, config, candleOpenTime),
            "Candle open at 15:54 ET is outside block window — demonstrates why close time must be used",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // resolveWatchlist (via onStartup) — driven by UniversePort.isMarketOpen
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `onStartup subscribes only symbols whose market is open according to the universe port`() =
        runTest(testDispatcher) {
            // AAPL market is open; SAP market is closed.
            val universePort =
                mockk<UniversePort> {
                    every { isMarketOpen(aapl) } returns true
                    every { isMarketOpen(sap) } returns false
                    every { getMarketSchedule(aapl) } returns usSchedule
                    every { getMarketSchedule(sap) } returns euSchedule
                }
            val service = buildService(universePort = universePort)

            service.onStartup()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { equityHistoricalBarsPort.fetch5MinBars(aapl, any()) }
            coVerify(exactly = 0) { equityHistoricalBarsPort.fetch5MinBars(sap, any()) }
        }

    @Test
    fun `onStartup creates no subscriptions when no market is open`() =
        runTest(testDispatcher) {
            // defaultUniversePort returns false for all isMarketOpen calls.
            val service = buildService()

            service.onStartup()
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { equityHistoricalBarsPort.fetch5MinBars(any(), any()) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // subscribeSymbol — basic hot-subscribe behaviour
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `subscribeSymbol returns true for a new symbol and false if already active`() =
        runTest(testDispatcher) {
            val universePort =
                mockk<UniversePort> {
                    every { isMarketOpen(any()) } returns false
                    every { getMarketSchedule(any()) } returns usSchedule
                }
            val service = buildService(universePort = universePort)

            assertTrue(service.subscribeSymbol("AAPL", "US"), "First subscribe should return true")
            assertFalse(service.subscribeSymbol("AAPL", "US"), "Re-subscribe while active should return false")
        }
}
