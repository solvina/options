package cz.solvina.options.adapters.inbound.diagnostics

import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.shared.scheduling.ScheduledTaskRegistry
import cz.solvina.options.shared.scheduling.ScheduledTaskSnapshot
import cz.solvina.options.shared.scheduling.ScheduledTaskStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
class ScheduledTaskWatchdogScheduler(
    private val registry: ScheduledTaskRegistry,
    private val hangDiagnostics: HangDiagnostics,
    private val alertPort: AlertPort,
    private val clock: Clock,
    @Value("\${diagnostics.scheduled-task-watchdog.enabled:true}") private val enabled: Boolean,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val reported = ConcurrentHashMap.newKeySet<String>()

    @Scheduled(
        fixedDelayString = "\${diagnostics.scheduled-task-watchdog.check-ms:60000}",
        initialDelayString = "\${diagnostics.scheduled-task-watchdog.initial-delay-ms:120000}",
        scheduler = "criticalTaskScheduler",
    )
    fun check() {
        if (!enabled) return
        val now = clock.instant()
        val active = mutableSetOf<String>()
        for (task in registry.snapshots()) {
            val issue = task.issue(now) ?: continue
            active += issue.key
            if (!reported.add(issue.key)) continue
            if (issue.kind == IssueKind.OVERRUN) {
                hangDiagnostics.captureAndLog("scheduled task overrun: ${task.name}")
            }
            scope.launch {
                runCatching {
                    alertPort.send(issue.level, issue.title, issue.body)
                }.onFailure { e -> logger.warn(e) { "Scheduled-task watchdog alert failed: ${e.message}" } }
            }
        }
        reported.retainAll(active)
    }

    private fun ScheduledTaskSnapshot.issue(now: Instant): TaskIssue? {
        val started = lastStartedAt
        if (status == ScheduledTaskStatus.RUNNING && started != null) {
            val age = Duration.between(started, now)
            if (age > budget) {
                return TaskIssue(
                    key = "$name:overrun",
                    kind = IssueKind.OVERRUN,
                    level = AlertLevel.CRITICAL,
                    title = "Scheduled task overrun — $name",
                    body =
                        "$name has been running for ${age.toMinutes()}m, over its " +
                            "${budget.toMinutes()}m budget. A hang dump was written to the logs.",
                )
            }
        }

        val finished = lastFinishedAt
        val period = expectedPeriod
        if (finished != null && period != null) {
            val age = Duration.between(finished, now)
            if (age > period.multipliedBy(3)) {
                return TaskIssue(
                    key = "$name:missed-cycles",
                    kind = IssueKind.MISSED_CYCLES,
                    level = AlertLevel.CRITICAL,
                    title = "Scheduled task missed cycles — $name",
                    body = "$name last finished ${age.toMinutes()}m ago, beyond 3x its expected ${period.toMinutes()}m cadence.",
                )
            }
        }

        val durationMs = lastDurationMs ?: return null
        if (durationMs > budget.toMillis() * 0.8) {
            return TaskIssue(
                key = "$name:degradation",
                kind = IssueKind.DEGRADATION,
                level = AlertLevel.WARN,
                title = "Scheduled task near timeout — $name",
                body = "$name took ${durationMs / 1000}s against its ${budget.toMinutes()}m budget.",
            )
        }
        return null
    }
}

private data class TaskIssue(
    val key: String,
    val kind: IssueKind,
    val level: AlertLevel,
    val title: String,
    val body: String,
)

private enum class IssueKind {
    OVERRUN,
    MISSED_CYCLES,
    DEGRADATION,
}
