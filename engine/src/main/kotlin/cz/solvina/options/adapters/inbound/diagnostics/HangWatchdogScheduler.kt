package cz.solvina.options.adapters.inbound.diagnostics

import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOrdersRegistry
import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Auto-captures a [HangDiagnostics] dump the moment an order-fill wait has been outstanding far
 * longer than any legitimate fill should take — the "pending in the engine, nothing in TWS"
 * symptom of an [IbkrOrdersRegistry] waiter that TWS will never resolve (order never landed, or a
 * reconnect broke the id association). This is the evidence-gathering step: the dump shows exactly
 * which `await` is parked and who holds which lock, so the real fix targets the confirmed hang.
 *
 * Each stuck order id is reported once (rising edge); ids that resolve are forgotten so a later,
 * independent stick re-reports. A dump for a genuinely hung engine is worth the log volume.
 */
@Component
class HangWatchdogScheduler(
    private val ordersRegistry: IbkrOrdersRegistry,
    private val hangDiagnostics: HangDiagnostics,
    private val alertPort: AlertPort,
    @Value("\${diagnostics.hang-watchdog.stuck-wait-minutes:5}") private val stuckWaitMinutes: Long,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val reported = ConcurrentHashMap.newKeySet<Int>()

    @Scheduled(
        fixedDelayString = "\${diagnostics.hang-watchdog.check-ms:60000}",
        initialDelayString = "\${diagnostics.hang-watchdog.initial-delay-ms:120000}",
        scheduler = "criticalTaskScheduler",
    )
    fun check() {
        val thresholdMs = stuckWaitMinutes * 60_000
        val stuck = ordersRegistry.outstandingWaiters().filter { it.ageMs >= thresholdMs }
        val stuckIds = stuck.map { it.orderId }.toSet()

        // Forget resolved ids so a future re-stick re-reports; then report only the newly-stuck.
        reported.retainAll(stuckIds)
        val newlyStuck = stuck.filter { reported.add(it.orderId) }
        if (newlyStuck.isEmpty()) return

        val summary = newlyStuck.joinToString { "order ${it.orderId} (${it.ageMs / 60_000}m, ${it.symbol ?: "?"})" }
        hangDiagnostics.captureAndLog("hang watchdog: order-fill wait(s) stuck > ${stuckWaitMinutes}m: $summary")

        scope.launch {
            runCatching {
                alertPort.send(
                    AlertLevel.CRITICAL,
                    "Engine hang suspected — stuck order-fill wait",
                    "The engine is waiting on a broker fill that TWS has not resolved for over " +
                        "${stuckWaitMinutes}m: $summary. A full coroutine + thread dump was written to the logs. " +
                        "This is the 'pending in the engine, nothing in TWS' condition.",
                )
            }.onFailure { e -> logger.warn(e) { "Hang-watchdog alert failed: ${e.message}" } }
        }
    }
}
