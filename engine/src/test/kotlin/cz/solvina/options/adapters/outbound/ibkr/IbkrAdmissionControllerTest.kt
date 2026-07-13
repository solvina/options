package cz.solvina.options.adapters.outbound.ibkr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
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

    @Test
    fun `historical requests beyond the window wait until the window frees`() =
        runTest {
            val limiter =
                IbkrAdmissionController(
                    IbkrAdmissionConfig(historicalMaxPer10Min = 2, historicalMinSpacingMs = 0, historicalMaxInFlight = 10),
                    schedulerClock(testScheduler),
                )

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
                IbkrAdmissionController(
                    IbkrAdmissionConfig(historicalMaxPer10Min = 100, historicalMinSpacingMs = 2_000, historicalMaxInFlight = 10),
                    schedulerClock(testScheduler),
                )
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
                IbkrAdmissionController(
                    IbkrAdmissionConfig(historicalMaxPer10Min = 100, historicalMinSpacingMs = 0, historicalMaxInFlight = 2),
                    schedulerClock(testScheduler),
                )
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
            val limiter =
                IbkrAdmissionController(
                    IbkrAdmissionConfig(historicalMaxPer10Min = 100, historicalMinSpacingMs = 0, pacingBackoffMs = 15_000),
                    schedulerClock(testScheduler),
                )
            limiter.notePacingViolation(162)
            val before = testScheduler.currentTime
            limiter.acquireHistorical()
            limiter.releaseHistorical()
            assertEquals(15_000L, testScheduler.currentTime - before, "next request should wait out the pacing backoff")
        }

    @Test
    fun `contract-details lookups serialise under the in-flight cap`() =
        runTest {
            val limiter = IbkrAdmissionController(IbkrAdmissionConfig(contractDetailsMaxInFlight = 1), schedulerClock(testScheduler))
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

    @Test
    fun `market-data lines are bounded and released`() =
        runTest {
            val limiter = IbkrAdmissionController(IbkrAdmissionConfig(marketDataLines = 2), schedulerClock(testScheduler))
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
}
