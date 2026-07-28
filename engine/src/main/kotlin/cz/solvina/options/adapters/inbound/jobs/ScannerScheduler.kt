package cz.solvina.options.adapters.inbound.jobs

import cz.solvina.options.adapters.inbound.diagnostics.HangDiagnostics
import cz.solvina.options.domain.features.connection.status.ConnectionStatusPort
import cz.solvina.options.domain.features.scanner.ScannerPort
import cz.solvina.options.domain.features.scanner.TradingKillSwitch
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

@Component
class ScannerScheduler(
    private val scannerPort: ScannerPort,
    private val connectionStatusPort: ConnectionStatusPort,
    private val killSwitch: TradingKillSwitch,
    private val hangDiagnostics: HangDiagnostics,
    @Value("\${scanner.run-timeout-minutes:10}") private val runTimeoutMinutes: Long,
) {
    // Kept as a diagnostic, but it cannot actually guard re-entry for a cron @Scheduled method:
    // Spring's ReschedulingRunnable computes the next fire time only AFTER the current invocation
    // returns, so a scan that never returns is never re-triggered and this flag is never re-read.
    // That is exactly how 2026-07-28 played out — the 16:00 scan wedged mid-chain-fetch and the
    // scanner went silent for hours without logging even one "still running" warning, because no
    // later invocation ever started. The timeout below is what actually protects the schedule.
    private val scanInProgress = AtomicBoolean(false)

    @Scheduled(cron = "\${scanner.cron:0 */15 9-22 * * MON-FRI}", zone = "Europe/Berlin")
    fun runScan() {
        if (killSwitch.scannerPaused) {
            logger.info { "Scanner skipped: paused by kill switch" }
            return
        }
        if (!connectionStatusPort.isConnected()) {
            logger.warn { "Scanner skipped: IBKR not connected" }
            return
        }
        if (!scanInProgress.compareAndSet(false, true)) {
            logger.warn { "Scanner skipped: previous scan still running (overruns the 15-min cadence)" }
            return
        }
        try {
            logger.info { "Scheduled scanner run triggered" }
            runBlocking {
                // A bounded scan is the difference between losing one run and losing the rest of the
                // session. Every individual IBKR await downstream is already bounded, so blowing
                // this budget means something unbounded slipped in — capture the evidence before
                // cancelling, because by the next run the stuck coroutine is gone.
                val completed =
                    withTimeoutOrNull(runTimeoutMinutes.minutes) {
                        runCatching { scannerPort.scan() }
                            .onFailure { e -> logger.error(e) { "Scanner run failed: ${e.message}" } }
                    }
                if (completed == null) {
                    logger.error { "Scanner run exceeded ${runTimeoutMinutes}m and was cancelled — capturing hang diagnostics" }
                    runCatching { hangDiagnostics.captureAndLog("scanner run exceeded ${runTimeoutMinutes}m") }
                        .onFailure { logger.warn { "Hang diagnostics capture failed: ${it.message}" } }
                }
            }
        } finally {
            scanInProgress.set(false)
        }
    }
}
