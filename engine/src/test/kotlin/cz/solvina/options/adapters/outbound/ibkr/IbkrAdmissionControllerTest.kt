package cz.solvina.options.adapters.outbound.ibkr

import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.features.market.MarketDataPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IbkrAdmissionControllerTest {
    /** A Clock backed by the coroutine test scheduler's virtual time, so delay() and the limiter's
     *  window math advance in lockstep — making time-dependent behaviour deterministic. */
    private fun schedulerClock(scheduler: TestCoroutineScheduler): Clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = Instant.ofEpochMilli(scheduler.currentTime)

            override fun millis(): Long = scheduler.currentTime
        }

    // Alerts launch fire-and-forget; an unconfined test dispatcher runs them inline at launch so
    // assertions right after the triggering acquire see them deterministically.
    private fun TestScope.controller(
        config: IbkrAdmissionConfig,
        alertPort: AlertPort = NoopAlertPort,
    ): IbkrAdmissionController =
        IbkrAdmissionController(config, schedulerClock(testScheduler), alertPort, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    /** 10 lines: EXIT 4 + FLAG 2 + EXEC 2 reserved, 2 leftover. */
    private fun partitionConfig(scannerCap: Int = 5) =
        IbkrAdmissionConfig(
            marketDataLines = 10,
            exitReserve = 4,
            flagReserve = 2,
            execReserve = 2,
            scannerLineConcurrency = scannerCap,
            starvationAlertMs = 2_000,
        )

    // ------------------------------------------------------------------ historical + contract details

    @Test
    fun `historical requests beyond the window wait until the window frees`() =
        runTest {
            val limiter = controller(IbkrAdmissionConfig(historicalMaxPer10Min = 2, historicalMinSpacingMs = 0, historicalMaxInFlight = 10))

            limiter.acquireHistorical()
            limiter.releaseHistorical()
            limiter.acquireHistorical()
            limiter.releaseHistorical()

            val before = testScheduler.currentTime
            limiter.acquireHistorical() // 3rd — window is full, must wait ~10 min
            limiter.releaseHistorical()

            assertEquals(600_000L, testScheduler.currentTime - before, "3rd request should wait for the 10-min window to free")
        }

    @Test
    fun `historical min-spacing is enforced between consecutive requests`() =
        runTest {
            val limiter =
                controller(IbkrAdmissionConfig(historicalMaxPer10Min = 100, historicalMinSpacingMs = 2_000, historicalMaxInFlight = 10))
            limiter.acquireHistorical()
            limiter.releaseHistorical()
            val before = testScheduler.currentTime
            limiter.acquireHistorical()
            limiter.releaseHistorical()
            assertEquals(2_000L, testScheduler.currentTime - before, "2nd request should wait the min spacing")
        }

    @Test
    fun `in-flight cap blocks the next acquire until a release`() =
        runTest {
            val limiter =
                controller(IbkrAdmissionConfig(historicalMaxPer10Min = 100, historicalMinSpacingMs = 0, historicalMaxInFlight = 2))
            limiter.acquireHistorical()
            limiter.acquireHistorical()

            var thirdAcquired = false
            val job =
                launch {
                    limiter.acquireHistorical()
                    thirdAcquired = true
                }
            testScheduler.runCurrent()
            assertFalse(thirdAcquired, "3rd acquire must block while 2 are in flight")

            limiter.releaseHistorical()
            testScheduler.runCurrent()
            assertTrue(thirdAcquired, "3rd acquire proceeds after a release")
            job.cancel()
        }

    @Test
    fun `a pacing violation delays the next historical request`() =
        runTest {
            val limiter = controller(IbkrAdmissionConfig(historicalMaxPer10Min = 100, historicalMinSpacingMs = 0, pacingBackoffMs = 15_000))
            limiter.notePacingViolation(162)
            val before = testScheduler.currentTime
            limiter.acquireHistorical()
            limiter.releaseHistorical()
            assertEquals(15_000L, testScheduler.currentTime - before, "next request should wait out the pacing backoff")
            assertEquals(1L, limiter.brokerLimitHitCounts()[162], "the violation must be counted")
        }

    @Test
    fun `contract-details lookups serialise under the in-flight cap`() =
        runTest {
            val limiter = controller(IbkrAdmissionConfig(contractDetailsMaxInFlight = 1))
            val firstStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var secondStarted = false

            val j1 =
                launch {
                    limiter.withContractDetails {
                        firstStarted.complete(Unit)
                        release.await()
                    }
                }
            firstStarted.await()
            val j2 = launch { limiter.withContractDetails { secondStarted = true } }
            testScheduler.runCurrent()
            assertFalse(secondStarted, "2nd contract-details lookup must wait for the 1st to release")

            release.complete(Unit)
            testScheduler.runCurrent()
            assertTrue(secondStarted, "2nd proceeds after the 1st releases")
            j1.cancel()
            j2.cancel()
        }

    // ------------------------------------------------------------------ partitioned line budget

    @Test
    fun `market-data lines are bounded and released`() =
        runTest {
            val limiter = controller(IbkrAdmissionConfig(marketDataLines = 2, exitReserve = 0, flagReserve = 0, execReserve = 0))
            limiter.acquireMarketDataLine()
            limiter.acquireMarketDataLine()
            assertEquals(0, limiter.availableMarketDataLines())

            var thirdAcquired = false
            val job =
                launch {
                    limiter.acquireMarketDataLine()
                    thirdAcquired = true
                }
            testScheduler.runCurrent()
            assertFalse(thirdAcquired, "3rd line must block when both are held")

            limiter.releaseMarketDataLine()
            testScheduler.runCurrent()
            assertTrue(thirdAcquired, "3rd line acquires after a release")
            assertEquals(0, limiter.availableMarketDataLines())
            job.cancel()
        }

    @Test
    fun `scanner may only draw leftover beyond the high-class reserves`() =
        runTest {
            val limiter = controller(partitionConfig())
            // 10 lines, 8 reserved → scanner leftover is 2.
            assertTrue(limiter.tryAcquireMarketDataLine(MarketDataPriority.SCANNER, 100))
            assertTrue(limiter.tryAcquireMarketDataLine(MarketDataPriority.SCANNER, 100))
            assertFalse(
                limiter.tryAcquireMarketDataLine(MarketDataPriority.SCANNER, 100),
                "3rd scanner line would dip into unmet high-class reserves",
            )
            assertEquals(1L, limiter.snapshot().lineClasses["SCANNER"]?.timeouts, "the bounded wait must count as a timeout")
        }

    @Test
    fun `scanner concurrency sub-cap holds even with plenty of leftover`() =
        runTest {
            val limiter =
                controller(
                    IbkrAdmissionConfig(
                        marketDataLines = 20,
                        exitReserve = 4,
                        flagReserve = 2,
                        execReserve = 2,
                        scannerLineConcurrency = 2,
                    ),
                )
            assertTrue(limiter.tryAcquireMarketDataLine(MarketDataPriority.SCANNER, 100))
            assertTrue(limiter.tryAcquireMarketDataLine(MarketDataPriority.SCANNER, 100))
            assertFalse(limiter.tryAcquireMarketDataLine(MarketDataPriority.SCANNER, 100), "sub-cap is 2")
            limiter.releaseMarketDataLine(MarketDataPriority.SCANNER)
            assertTrue(limiter.tryAcquireMarketDataLine(MarketDataPriority.SCANNER, 100), "a release frees a scanner slot")
        }

    @Test
    fun `every high class can always reach its full reserve`() =
        runTest {
            val limiter = controller(partitionConfig())
            // EXIT grabs everything it can: 4 reserved + 2 leftover = 6 (must leave FLAG 2 + EXEC 2).
            repeat(6) { limiter.acquireMarketDataLine(MarketDataPriority.EXIT) }
            var exitSeventh = false
            val job =
                launch {
                    limiter.acquireMarketDataLine(MarketDataPriority.EXIT)
                    exitSeventh = true
                }
            testScheduler.runCurrent()
            assertFalse(exitSeventh, "7th EXIT line would eat FLAG/EXEC reserves")

            // FLAG and EXEC still get their full reserves instantly.
            repeat(2) { limiter.acquireMarketDataLine(MarketDataPriority.FLAG) }
            repeat(2) { limiter.acquireMarketDataLine(MarketDataPriority.EXEC) }
            assertEquals(0, limiter.availableMarketDataLines())
            job.cancel()
        }

    @Test
    fun `a release wakes a queued high-priority waiter before a queued scanner`() =
        runTest {
            val limiter = controller(partitionConfig())
            repeat(6) { limiter.acquireMarketDataLine(MarketDataPriority.EXIT) } // pool at high-class max

            var scannerGot = false
            var exitGot = false
            val scannerJob = launch { scannerGot = limiter.tryAcquireMarketDataLine(MarketDataPriority.SCANNER, 60_000) }
            testScheduler.runCurrent()
            val exitJob =
                launch {
                    limiter.acquireMarketDataLine(MarketDataPriority.EXIT)
                    exitGot = true
                }
            testScheduler.runCurrent()
            assertFalse(scannerGot || exitGot, "both must be queued while the pool is at its bound")

            limiter.releaseMarketDataLine(MarketDataPriority.EXIT)
            testScheduler.runCurrent()
            assertTrue(exitGot, "EXIT (queued later) must be granted first")
            assertFalse(scannerGot, "scanner keeps waiting — the freed line went to EXIT")
            scannerJob.cancel()
            exitJob.cancel()
        }

    @Test
    fun `a starved high-priority acquire alerts the operator`() =
        runTest {
            val alerts = RecordingAlertPort()
            val limiter = controller(partitionConfig(), alertPort = alerts)
            repeat(6) { limiter.acquireMarketDataLine(MarketDataPriority.EXIT) }

            val waiter =
                launch {
                    limiter.acquireMarketDataLine(MarketDataPriority.EXIT) // blocks — pool at high-class max
                }
            testScheduler.runCurrent()
            delay(3_000) // past starvation-alert-ms (2s)
            limiter.releaseMarketDataLine(MarketDataPriority.EXIT)
            testScheduler.advanceUntilIdle()

            assertTrue(waiter.isCompleted, "the waiter must be granted after the release")
            assertEquals(1, alerts.alerts.size, "a wait past the threshold must alert exactly once")
            assertTrue(
                alerts.alerts
                    .single()
                    .second
                    .contains("EXIT"),
                "the alert must name the starved class",
            )
        }

    @Test
    fun `snapshot reports held lines and wait stats per class`() =
        runTest {
            val limiter = controller(partitionConfig())
            limiter.acquireMarketDataLine(MarketDataPriority.FLAG)
            limiter.acquireMarketDataLine(MarketDataPriority.EXEC)
            val snap = limiter.snapshot()
            assertEquals(1, snap.lineClasses["FLAG"]?.held)
            assertEquals(1, snap.lineClasses["EXEC"]?.held)
            assertEquals(8, snap.linesAvailable)
            assertEquals(10, snap.linesTotal)
        }
}
