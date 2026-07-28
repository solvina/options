package cz.solvina.options.shared.scheduling

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.DurationUnit

enum class ScheduledTaskStatus {
    IDLE,
    RUNNING,
    FAILED,
    TIMED_OUT,
}

data class ScheduledTaskSnapshot(
    val name: String,
    val status: ScheduledTaskStatus,
    val lastStartedAt: Instant?,
    val lastFinishedAt: Instant?,
    val lastDurationMs: Long?,
    val consecutiveFailures: Int,
    val runCount: Long,
    val expectedPeriod: Duration?,
    val budget: Duration,
    val lastError: String?,
)

data class ScheduledTaskRun(
    val name: String,
    val startedAt: Instant,
)

@Component
class ScheduledTaskRegistry(
    private val clock: Clock,
) {
    private val tasks = ConcurrentHashMap<String, MutableScheduledTask>()

    init {
        activeScheduledTaskRegistry = this
    }

    fun started(
        name: String,
        budget: kotlin.time.Duration,
        expectedPeriod: kotlin.time.Duration?,
    ): ScheduledTaskRun {
        val now = clock.instant()
        synchronized(tasks) {
            val task = tasks.computeIfAbsent(name) { MutableScheduledTask(name) }
            task.status = ScheduledTaskStatus.RUNNING
            task.lastStartedAt = now
            task.budget = budget.toJavaDuration()
            task.expectedPeriod = expectedPeriod?.toJavaDuration()
        }
        return ScheduledTaskRun(name, now)
    }

    fun finished(
        run: ScheduledTaskRun,
        timedOut: Boolean,
        error: Throwable?,
    ) {
        val now = clock.instant()
        synchronized(tasks) {
            val task = tasks.computeIfAbsent(run.name) { MutableScheduledTask(run.name) }
            val durationMs = Duration.between(run.startedAt, now).toMillis().coerceAtLeast(0)
            task.lastFinishedAt = now
            task.lastDurationMs = durationMs
            task.runCount += 1
            task.lastError = error?.message
            when {
                timedOut -> {
                    task.status = ScheduledTaskStatus.TIMED_OUT
                    task.consecutiveFailures += 1
                    if (task.lastError == null) task.lastError = "Timed out after ${task.budget.toMinutes()}m"
                }
                error != null -> {
                    task.status = ScheduledTaskStatus.FAILED
                    task.consecutiveFailures += 1
                }
                else -> {
                    task.status = ScheduledTaskStatus.IDLE
                    task.consecutiveFailures = 0
                }
            }
        }
    }

    fun snapshots(): List<ScheduledTaskSnapshot> =
        synchronized(tasks) {
            tasks.values.map { it.snapshot() }.sortedBy { it.name }
        }

    private fun MutableScheduledTask.snapshot() =
        ScheduledTaskSnapshot(
            name = name,
            status = status,
            lastStartedAt = lastStartedAt,
            lastFinishedAt = lastFinishedAt,
            lastDurationMs = lastDurationMs,
            consecutiveFailures = consecutiveFailures,
            runCount = runCount,
            expectedPeriod = expectedPeriod,
            budget = budget,
            lastError = lastError,
        )
}

private class MutableScheduledTask(
    val name: String,
) {
    var status: ScheduledTaskStatus = ScheduledTaskStatus.IDLE
    var lastStartedAt: Instant? = null
    var lastFinishedAt: Instant? = null
    var lastDurationMs: Long? = null
    var consecutiveFailures: Int = 0
    var runCount: Long = 0
    var expectedPeriod: Duration? = null
    var budget: Duration = Duration.ofMinutes(10)
    var lastError: String? = null
}

@Volatile
private var activeScheduledTaskRegistry: ScheduledTaskRegistry? = null

internal fun currentScheduledTaskRegistry(): ScheduledTaskRegistry? = activeScheduledTaskRegistry

private fun kotlin.time.Duration.toJavaDuration(): Duration = Duration.ofMillis(toLong(DurationUnit.MILLISECONDS))
