package cz.solvina.options.adapters.inbound.diagnostics

import cz.solvina.options.adapters.outbound.ibkr.account.IbkrOrdersRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.debug.DebugProbes
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Builds a one-shot "why is the engine stuck?" report: the outstanding order-fill waits (the
 * direct "pending in the engine, nothing in TWS" symptom), a coroutine dump (which suspended
 * `await` is parked and its chain), and a JVM thread dump (native/blocked threads and held
 * monitors — e.g. the IBKR reader thread or a monitor-guarded critical section).
 *
 * The coroutine dump needs [DebugProbes] installed at startup (see CoroutineDebugInstaller); the
 * thread dump always works. Read-only — this never touches control flow.
 */
@Component
class HangDiagnostics(
    private val ordersRegistry: IbkrOrdersRegistry,
) {
    /** Build the report and log it at WARN. Returns it too (for a manual trigger). */
    fun captureAndLog(reason: String): String = buildReport(reason).also { logger.warn { "\n$it" } }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun buildCoroutineDump(): String =
        if (DebugProbes.isInstalled) {
            runCatching { dumpCoroutines() }
                .getOrElse { "(coroutine dump failed: ${it.message})" }
        } else {
            "(DebugProbes not installed — set diagnostics.coroutine-debug.enabled=true and restart)"
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun buildReport(reason: String): String {
        val now = Instant.now()
        return buildString {
            appendLine("================ HANG DIAGNOSTIC DUMP ================")
            appendLine("reason : $reason")
            appendLine("at     : $now")
            appendLine()

            val waiters = ordersRegistry.outstandingWaiters(now)
            appendLine("---- Outstanding order-fill waits (${waiters.size}) ----")
            if (waiters.isEmpty()) {
                appendLine("(none)")
            } else {
                waiters.forEach { w ->
                    appendLine(
                        "orderId=${w.orderId} age=${w.ageMs / 1000}s status=${w.lastStatus ?: "?"} " +
                            "symbol=${w.symbol ?: "?"} since=${w.since}",
                    )
                }
            }
            appendLine()

            appendLine("---- Coroutine dump ----")
            appendLine(buildCoroutineDump())
            appendLine()

            appendLine("---- Thread dump ----")
            runCatching { appendLine(threadDump()) }
                .onFailure { appendLine("(thread dump failed: ${it.message})") }
            appendLine("=====================================================")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun dumpCoroutines(): String {
        val buffer = ByteArrayOutputStream()
        PrintStream(buffer, true, Charsets.UTF_8).use { DebugProbes.dumpCoroutines(it) }
        return buffer.toString(Charsets.UTF_8)
    }

    private fun threadDump(): String {
        val bean = ManagementFactory.getThreadMXBean()
        return buildString {
            bean.dumpAllThreads(true, true).sortedBy { it.threadName }.forEach { info ->
                appendLine("\"${info.threadName}\" #${info.threadId} ${info.threadState}")
                info.lockName?.let {
                    appendLine("    - waiting on $it (owner: ${info.lockOwnerName ?: "none"} #${info.lockOwnerId})")
                }
                info.stackTrace.forEach { frame -> appendLine("    at $frame") }
                info.lockedMonitors.takeIf { it.isNotEmpty() }?.let { monitors ->
                    appendLine("    locked monitors:")
                    monitors.forEach { m -> appendLine("      - $m (depth ${m.lockedStackDepth})") }
                }
                appendLine()
            }
        }
    }
}
