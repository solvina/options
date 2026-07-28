package cz.solvina.options.scanner

import cz.solvina.options.adapters.inbound.diagnostics.HangDiagnostics
import cz.solvina.options.adapters.inbound.jobs.ScannerScheduler
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.scanner.ScannerPort
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * On 2026-07-28 the 16:00 scan wedged on a symbol's option-chain fetch and never returned. Spring
 * reschedules a cron `@Scheduled` task only after the invocation returns, so the scanner produced
 * no further runs — and no warning either, because the in-progress guard is only read by a *later*
 * invocation that never happened. The session was lost silently.
 *
 * The contract these tests pin: `runScan` always returns.
 */
class ScannerSchedulerTimeoutTest {
    private val connection = mockk<ConnectionStatusPort> { every { isConnected() } returns true }
    private val killSwitch = mockk<TradingKillSwitch> { every { scannerPaused } returns false }
    private val diagnostics =
        mockk<HangDiagnostics>(relaxed = true) {
            every { captureAndLog(any()) } returns "dump"
        }

    private fun scheduler(
        port: ScannerPort,
        timeoutMinutes: Long,
    ) = ScannerScheduler(port, connection, killSwitch, diagnostics, timeoutMinutes)

    @Test
    fun `a scan that never completes still returns, so the cron keeps firing`() {
        // Suspends forever — the shape of an unbounded await on an IBKR response that never arrives.
        val neverCompletes =
            object : ScannerPort {
                override suspend fun scan() {
                    CompletableDeferred<Unit>().await()
                }
            }
        // 1 minute is the smallest the API takes; the assertion is that it returns AT ALL, well
        // inside the 5-minute test budget, rather than blocking the scheduler thread for ever.
        val scheduler = scheduler(neverCompletes, timeoutMinutes = 1)

        val done = CompletableDeferred<Unit>()
        val worker =
            Thread {
                scheduler.runScan()
                done.complete(Unit)
            }.apply {
                isDaemon = true
                start()
            }

        worker.join(TimeUnit.MINUTES.toMillis(5))
        assertTrue(done.isCompleted, "runScan did not return — the cron would never fire again")
    }

    @Test
    fun `a hang dump is captured when the budget is blown`() {
        val neverCompletes =
            object : ScannerPort {
                override suspend fun scan() {
                    CompletableDeferred<Unit>().await()
                }
            }
        scheduler(neverCompletes, timeoutMinutes = 1).runScan()
        io.mockk.verify { diagnostics.captureAndLog(any()) }
    }

    @Test
    fun `a normal scan is unaffected and captures no dump`() {
        var ran = false
        val fast =
            object : ScannerPort {
                override suspend fun scan() {
                    ran = true
                }
            }
        scheduler(fast, timeoutMinutes = 10).runScan()
        assertTrue(ran, "scan should have run")
        io.mockk.verify(exactly = 0) { diagnostics.captureAndLog(any()) }
    }
}
