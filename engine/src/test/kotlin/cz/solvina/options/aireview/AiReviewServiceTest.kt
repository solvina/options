package cz.solvina.options.aireview

import cz.solvina.options.domain.features.aireview.AiFailureKind
import cz.solvina.options.domain.features.aireview.AiResultStatus
import cz.solvina.options.domain.features.aireview.AiReviewConfig
import cz.solvina.options.domain.features.aireview.AiReviewService
import cz.solvina.options.domain.features.aireview.AiReviewerState
import cz.solvina.options.domain.features.aireview.AiTaskStatus
import cz.solvina.options.domain.features.aireview.AiVerdict
import cz.solvina.options.domain.features.aireview.PromptOutcome
import cz.solvina.options.domain.features.aireview.ResultSubmission
import cz.solvina.options.domain.features.aireview.ReviewerConfig
import cz.solvina.options.domain.features.aireview.SubmitOutcome
import cz.solvina.options.testutil.InMemoryAiReviewStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiReviewServiceTest {
    private val ny = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 11, 19)

    /** 10:30 NY: before the 13:00 main decision. */
    private val morning = day.atTime(10, 30).atZone(ny).toInstant()

    private class SettableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = SettableClock(morning)
    private val store = InMemoryAiReviewStore()
    private val config =
        AiReviewConfig(
            enabled = true,
            apiToken = "t",
            reviewers = listOf(ReviewerConfig("claude-opus", primary = true), ReviewerConfig("openai-gpt")),
            dailyTaskCap = 3,
        )
    private val service = AiReviewService(store, config, clock)

    private fun snapshot(
        symbol: String = "NUE",
        asOf: Instant = morning,
    ) = testSnapshot(symbol, asOf)

    private fun submission(
        leaseToken: String,
        verdict: AiVerdict = AiVerdict.VETO,
        reviewer: String = "claude-opus",
        confidence: Double = 0.8,
        contractVersion: String = "1",
    ) = ResultSubmission(reviewer, leaseToken, contractVersion, verdict, confidence, "earnings risk")

    @Test
    fun `a candidate before a decision becomes an OPEN task due at the next decision, one result per reviewer`() =
        runTest {
            val task = assertNotNull(service.createTask(snapshot()))

            assertEquals(AiTaskStatus.OPEN, task.status)
            assertEquals(day.atTime(13, 0).atZone(ny).toInstant(), task.deadlineAt)
            assertEquals(
                setOf("claude-opus", "openai-gpt"),
                store.results.keys
                    .map { it.second }
                    .toSet(),
            )
        }

    @Test
    fun `between decisions the deadline is the top-up, after the last decision the task is SKIPPED`() =
        runTest {
            val afternoon = day.atTime(14, 0).atZone(ny).toInstant()
            assertEquals(day.atTime(15, 0).atZone(ny).toInstant(), service.nextDecision(afternoon))

            val late = day.atTime(15, 10).atZone(ny).toInstant()
            val task = assertNotNull(service.createTask(snapshot(asOf = late)))
            assertEquals(AiTaskStatus.SKIPPED, task.status)
            assertEquals("AFTER_FINAL_DECISION", task.skipReason)
            assertTrue(store.results.isEmpty(), "nobody is asked to review a title no decision will use")
        }

    @Test
    fun `one task per title and day, and the daily cap skips the rest`() =
        runTest {
            assertNotNull(service.createTask(snapshot("NUE")))
            assertNull(service.createTask(snapshot("NUE")), "re-offer after restart or unfilled entry")
            service.createTask(snapshot("MPC"))
            service.createTask(snapshot("WELL"))

            val capped = assertNotNull(service.createTask(snapshot("SPG")))
            assertEquals(AiTaskStatus.SKIPPED, capped.status)
            assertEquals("DAILY_CAP", capped.skipReason)
        }

    @Test
    fun `disabled review creates nothing and hands out nothing`() =
        runTest {
            val off = AiReviewService(store, config.copy(enabled = false), clock)
            assertNull(off.createTask(snapshot()))
            assertNull(off.claim("claude-opus", listOf("1"), null))
        }

    @Test
    fun `claim leases the task, unknown reviewers and unsupported contracts get nothing`() =
        runTest {
            service.createTask(snapshot())

            assertNull(service.claim("someone-else", listOf("1"), null))
            assertNull(service.claim("claude-opus", listOf("2"), null))

            val claimed = assertNotNull(service.claim("claude-opus", listOf("1"), null))
            assertEquals(AiResultStatus.IN_PROGRESS, claimed.result.status)
            assertEquals(1, claimed.result.attempts)
            assertNull(service.claim("claude-opus", listOf("1"), null), "leased: not handed out twice")
            assertNotNull(service.claim("openai-gpt", listOf("1"), null), "each reviewer has its own row")
        }

    @Test
    fun `a result is stored, flagged late after the deadline, and a retry of the same POST is idempotent`() =
        runTest {
            val task = assertNotNull(service.createTask(snapshot()))
            val lease = service.claim("claude-opus", listOf("1"), null)!!.result.leaseToken!!

            assertEquals(SubmitOutcome.Accepted(late = false), service.submitResult(task.id, submission(lease)))
            assertEquals(SubmitOutcome.Accepted(late = false), service.submitResult(task.id, submission(lease)))
            assertEquals(SubmitOutcome.Conflict, service.submitResult(task.id, submission(lease, AiVerdict.APPROVE)))
            assertEquals(SubmitOutcome.Conflict, service.submitResult(task.id, submission("other-lease")))

            val stored = store.results[task.id to "claude-opus"]!!
            assertEquals(AiResultStatus.DONE, stored.status)
            assertEquals(AiVerdict.VETO, stored.verdict)

            val lease2 = service.claim("openai-gpt", listOf("1"), null)!!.result.leaseToken!!
            clock.now = day.atTime(13, 5).atZone(ny).toInstant()
            assertEquals(SubmitOutcome.Accepted(late = true), service.submitResult(task.id, submission(lease2, reviewer = "openai-gpt")))
        }

    @Test
    fun `invalid results are refused and the task stays open`() =
        runTest {
            val task = assertNotNull(service.createTask(snapshot()))
            val lease = service.claim("claude-opus", listOf("1"), null)!!.result.leaseToken!!

            assertIs<SubmitOutcome.Invalid>(service.submitResult(task.id, submission(lease, confidence = 1.5)))
            assertIs<SubmitOutcome.Invalid>(service.submitResult(task.id, submission(lease, contractVersion = "9")))
            assertEquals(AiResultStatus.IN_PROGRESS, store.results[task.id to "claude-opus"]!!.status)
        }

    @Test
    fun `AUTH failures never use an attempt and mark the reviewer, other failures exhaust the task`() =
        runTest {
            val task = assertNotNull(service.createTask(snapshot()))

            repeat(5) {
                val lease = service.claim("claude-opus", listOf("1"), null)!!.result.leaseToken!!
                assertTrue(service.reportFailure(task.id, "claude-opus", lease, AiFailureKind.AUTH, "please run /login"))
            }
            assertEquals(0, store.results[task.id to "claude-opus"]!!.attempts)
            assertEquals(AiResultStatus.PENDING, store.results[task.id to "claude-opus"]!!.status)
            assertEquals(AiReviewerState.AUTH_FAILED, store.statuses["claude-opus"]!!.state)

            repeat(3) {
                val lease = service.claim("claude-opus", listOf("1"), null)!!.result.leaseToken!!
                service.reportFailure(task.id, "claude-opus", lease, AiFailureKind.TIMEOUT, "10 min")
            }
            assertEquals(AiResultStatus.FAILED, store.results[task.id to "claude-opus"]!!.status)
            assertNull(service.claim("claude-opus", listOf("1"), null))
            assertFalse(service.reportFailure(task.id, "claude-opus", "wrong", AiFailureKind.OTHER, "x"))
        }

    @Test
    fun `sweep returns expired leases to the queue and expires what missed the deadline`() =
        runTest {
            val task = assertNotNull(service.createTask(snapshot()))
            service.claim("claude-opus", listOf("1"), null)

            clock.now = morning.plus(Duration.ofMinutes(20))
            service.sweep()
            val back = store.results[task.id to "claude-opus"]!!
            assertEquals(AiResultStatus.PENDING, back.status)
            assertEquals(1, back.attempts, "a crash on a task still costs its attempt")

            clock.now = day.atTime(13, 1).atZone(ny).toInstant()
            service.sweep()
            assertEquals(AiResultStatus.EXPIRED, store.results[task.id to "claude-opus"]!!.status)
            assertEquals(AiResultStatus.EXPIRED, store.results[task.id to "openai-gpt"]!!.status)
            assertNull(service.claim("claude-opus", listOf("1"), null), "past the deadline nothing is handed out")
        }

    @Test
    fun `heartbeats drive reviewer state, silence is reported as SILENT`() =
        runTest {
            service.heartbeat("claude-opus", AiReviewerState.READY, null, "anthropic", "opus", "0.1")
            val views = service.reviewers().associateBy { it.config.id }
            assertEquals(AiReviewerState.READY, views["claude-opus"]!!.state)
            assertEquals(AiReviewerState.SILENT, views["openai-gpt"]!!.state)

            clock.now = morning.plus(Duration.ofMinutes(4))
            assertEquals(AiReviewerState.SILENT, service.reviewers().first { it.config.id == "claude-opus" }.state)
        }

    @Test
    fun `a prompt hash is registered once, different text under the same hash is a conflict`() =
        runTest {
            assertEquals(PromptOutcome.STORED, service.registerPrompt("abcd1234abcd1234", "claude-opus", "v1", "Review this"))
            assertEquals(PromptOutcome.STORED, service.registerPrompt("abcd1234abcd1234", "claude-opus", "v1", "Review this"))
            assertEquals(PromptOutcome.CONFLICT, service.registerPrompt("abcd1234abcd1234", "claude-opus", "v1", "Other text"))
        }
}
