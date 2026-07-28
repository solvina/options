package cz.solvina.options.adapters.inbound.jobs

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
 * starts to notice. On 2026-07-28 this took out the scanner for a whole session, and then the
 * earnings refresh.
 *
 * Every `@Scheduled` + `runBlocking` pair must go through here. The rule is mechanical: if a
 * scheduled method blocks on suspending work, it needs a deadline.
 */
fun runScheduled(
    name: String,
    timeout: Duration = 10.minutes,
    block: suspend () -> Unit,
) {
    runBlocking {
        val completed =
            withTimeoutOrNull(timeout) {
                runCatching { block() }
                    .onFailure { e -> logger.error(e) { "$name failed: ${e.message}" } }
            }
        if (completed == null) {
            logger.error { "$name exceeded $timeout and was cancelled — the schedule survives, this run did not" }
        }
    }
}
