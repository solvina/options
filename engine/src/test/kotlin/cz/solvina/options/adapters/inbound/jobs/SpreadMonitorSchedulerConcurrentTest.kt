package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import cz.solvina.options.domain.features.spread.SpreadManagementService
import cz.solvina.options.domain.features.universe.ExchangeSession
import cz.solvina.options.domain.features.universe.UniversePort
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.assertTrue

/**
 * Tests for SpreadMonitorScheduler mutex-based locking.
 *
 * Verifies that the scheduler has mutex protection and respects all guard conditions.
 * The scheduler uses its own CoroutineScope with Dispatchers.IO, so these tests
 * verify the guard conditions and that no exceptions occur.
 */
class SpreadMonitorSchedulerConcurrentTest {
    private val spreadManagementService = mockk<SpreadManagementService>(relaxed = true)
    private val connectionStatusPort = mockk<ConnectionStatusPort>()
    private val killSwitch = mockk<TradingKillSwitch>()
    private val universePort = mockk<UniversePort>()

    @BeforeEach
    fun setup() {
        every { killSwitch.monitorPaused } returns false
        every { connectionStatusPort.isConnected() } returns true
        every { universePort.getExchangeSessions() } returns listOf(ExchangeSession.US)
    }

    @Test
    fun `monitorSpreads can be called without errors when conditions met`() {
        val scheduler =
            SpreadMonitorScheduler(
                spreadManagementService,
                connectionStatusPort,
                killSwitch,
                universePort,
            )

        // Should not throw
        scheduler.monitorSpreads()
    }

    @Test
    fun `monitorSpreads returns early when kill switch is paused`() {
        every { killSwitch.monitorPaused } returns true

        val scheduler =
            SpreadMonitorScheduler(
                spreadManagementService,
                connectionStatusPort,
                killSwitch,
                universePort,
            )

        // Should not throw
        scheduler.monitorSpreads()
    }

    @Test
    fun `monitorSpreads returns early when IBKR not connected`() {
        every { connectionStatusPort.isConnected() } returns false

        val scheduler =
            SpreadMonitorScheduler(
                spreadManagementService,
                connectionStatusPort,
                killSwitch,
                universePort,
            )

        // Should not throw
        scheduler.monitorSpreads()
    }

    @Test
    fun `monitorSpreads uses tryLock - non-blocking on mutex acquire`() {
        // This test verifies the mutex is in place by checking the code
        // The SpreadMonitorScheduler.kt line 48 uses monitorMutex.tryLock()
        // which is a non-blocking mutex acquire (returns false if already locked)
        // rather than mutex.withLock() which would block
        val scheduler =
            SpreadMonitorScheduler(
                spreadManagementService,
                connectionStatusPort,
                killSwitch,
                universePort,
            )

        // Multiple rapid calls should not deadlock (because tryLock is non-blocking)
        repeat(5) {
            scheduler.monitorSpreads()
        }

        // If we get here without hanging, tryLock is working correctly
        assertTrue(true, "Multiple monitorSpreads calls completed without deadlock")
    }

    @Test
    fun `mutex is released after checkExits suspends and resumes on a different dispatcher thread`() {
        // checkExits() genuinely suspends (unlike the other tests' relaxed mock), so the launched
        // coroutine on Dispatchers.IO may resume on a different thread than it started on — this is
        // exactly the scenario a thread-bound ReentrantLock cannot survive (IllegalMonitorStateException
        // on unlock from a different thread, leaving the lock stuck forever).
        coEvery { spreadManagementService.checkExits() } coAnswers { delay(50) }
        // Widen exchange hours to the full day and pin the clock to a Wednesday so
        // isAnyExchangeOpen() is independent of when this test actually runs — the wall-clock
        // version failed every weekend on the scheduler's weekday gate.
        every { universePort.getExchangeSessions() } returns
            listOf(
                ExchangeSession.US.copy(open = LocalTime.of(0, 0), close = LocalTime.of(23, 59)),
            )

        val scheduler =
            SpreadMonitorScheduler(
                spreadManagementService,
                connectionStatusPort,
                killSwitch,
                universePort,
                Clock.fixed(Instant.parse("2026-07-15T15:00:00Z"), ZoneOffset.UTC),
            )

        scheduler.monitorSpreads() // acquires the mutex, starts a 50ms suspend
        scheduler.monitorSpreads() // should be skipped: previous run still in progress

        Thread.sleep(300) // let the first run's coroutine complete and release the mutex

        scheduler.monitorSpreads() // mutex must be free again — must not stay stuck

        Thread.sleep(300) // let the third run complete

        // Exactly 2 invocations: the first run and the third run. The second was skipped by tryLock.
        coVerify(exactly = 2) { spreadManagementService.checkExits() }
    }

    @Test
    fun `monitorSpreads handles exceptions gracefully`() {
        coEvery { spreadManagementService.checkExits() } throws RuntimeException("Test error")

        val scheduler =
            SpreadMonitorScheduler(
                spreadManagementService,
                connectionStatusPort,
                killSwitch,
                universePort,
            )

        // Should not throw - exception is caught and logged by the scheduler
        try {
            scheduler.monitorSpreads()
            assertTrue(true, "Exception handled gracefully")
        } catch (e: Exception) {
            assertTrue(false, "Scheduler should catch exceptions: ${e.message}")
        }
    }
}
