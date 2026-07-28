package cz.solvina.options.shared.scheduling

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Runs suspending work from a `@Scheduled` method with a hard ceiling.
 *
 * Spring's `ReschedulingRunnable` computes a task's next fire time only AFTER the current
 * invocation returns. A `runBlocking` that never completes therefore does not cost one run — it
 * ends that schedule for the lifetime of the process, silently, because no later invocation ever
 * starts to notice.
 */
fun runScheduled(
    name: String,
    timeout: Duration = 10.minutes,
    expectedPeriod: Duration? = null,
    onTimeout: (() -> Unit)? = null,
    block: suspend () -> Unit,
) {
    val run = currentScheduledTaskRegistry()?.started(name, timeout, expectedPeriod)
    var failure: Throwable? = null
    var timedOut = false
    try {
        runBlocking {
            val completed =
                withTimeoutOrNull(timeout) {
                    runCatching { block() }
                        .onFailure { e ->
                            failure = e
                            logger.error(e) { "$name failed: ${e.message}" }
                        }
                }
            if (completed == null) {
                timedOut = true
                logger.error { "$name exceeded $timeout and was cancelled" }
                runCatching { onTimeout?.invoke() }
                    .onFailure { logger.warn { "Hang diagnostics capture failed: ${it.message}" } }
            }
        }
    } catch (e: Throwable) {
        failure = e
        throw e
    } finally {
        if (run != null) currentScheduledTaskRegistry()?.finished(run, timedOut, failure)
    }
}
