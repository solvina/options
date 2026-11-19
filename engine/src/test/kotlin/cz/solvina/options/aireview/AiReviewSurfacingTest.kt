package cz.solvina.options.aireview

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import cz.solvina.options.domain.features.aireview.AiCandidateReviewRequester
import cz.solvina.options.domain.features.aireview.AiFailureKind
import cz.solvina.options.domain.features.aireview.AiReviewConfig
import cz.solvina.options.domain.features.aireview.AiReviewNotificationPort
import cz.solvina.options.domain.features.aireview.AiReviewService
import cz.solvina.options.domain.features.aireview.AiReviewerState
import cz.solvina.options.domain.features.aireview.AiReviewerWatchdog
import cz.solvina.options.domain.features.aireview.AiVerdict
import cz.solvina.options.domain.features.aireview.ResultSubmission
import cz.solvina.options.domain.features.aireview.ReviewDispatcher
import cz.solvina.options.domain.features.aireview.ReviewSnapshotBuilder
import cz.solvina.options.domain.features.aireview.ReviewerConfig
import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import cz.solvina.options.domain.features.notification.OpportunityNotificationPort
import cz.solvina.options.domain.features.notification.ReviewNote
import cz.solvina.options.domain.features.notification.SpreadOpportunity
import cz.solvina.options.testutil.InMemoryAiReviewStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiReviewSurfacingTest {
    private val ny = ZoneId.of("America/New_York")
    private val morning =
        LocalDate
            .of(2026, 11, 19)
            .atTime(10, 30)
            .atZone(ny)
            .toInstant()

    private class SettableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private class RecordingNotifier : OpportunityNotificationPort {
        val plain = mutableListOf<SpreadOpportunity>()
        val reviewed = mutableListOf<Pair<SpreadOpportunity, ReviewNote>>()
        val followUps = mutableListOf<ReviewNote>()

        override suspend fun notify(opportunity: SpreadOpportunity) {
            plain += opportunity
        }

        override suspend fun notifyReviewed(
            opportunity: SpreadOpportunity,
            review: ReviewNote,
            threadKey: String,
        ) {
            reviewed += opportunity to review
        }

        override suspend fun notifyReviewFollowUp(
            opportunity: SpreadOpportunity,
            review: ReviewNote,
            threadKey: String,
        ) {
            followUps += review
        }
    }

    private val clock = SettableClock(morning)
    private val store = InMemoryAiReviewStore()
    private val config =
        AiReviewConfig(
            enabled = true,
            reviewers = listOf(ReviewerConfig("claude-opus", primary = true), ReviewerConfig("openai-gpt")),
            emailWaitMinutes = 15,
        )
    private val service = AiReviewService(store, config, clock)
    private val notifier = RecordingNotifier()
    private val telegram = mutableListOf<String>()
    private val dispatcher =
        ReviewDispatcher(store, config, notifier, AiReviewNotificationPort { telegram += it }, jacksonObjectMapper(), clock)

    private suspend fun answer(
        reviewer: String,
        verdict: AiVerdict,
    ) {
        val claimed = assertNotNull(service.claim(reviewer, listOf("1"), null))
        service.submitResult(
            claimed.task.id,
            ResultSubmission(
                reviewer,
                claimed.result.leaseToken!!,
                "1",
                verdict,
                0.8,
                "Steel tariff ruling due before expiry.",
                risksJson = """[{"category":"EVENT","severity":"HIGH","description":"tariff ruling"}]""",
                sourcesJson = """[{"title":"Reuters","url":"https://example.com"}]""",
                model = "claude-opus-5-5",
            ),
        )
    }

    @Test
    fun `the email waits for the primary verdict and carries it, Telegram announces it once`() =
        runTest {
            service.createTask(testSnapshot(asOf = morning))
            dispatcher.dispatch()
            assertTrue(notifier.reviewed.isEmpty(), "nothing yet: waiting for the verdict")

            answer("openai-gpt", AiVerdict.APPROVE)
            answer("claude-opus", AiVerdict.VETO)
            dispatcher.dispatch()
            dispatcher.dispatch()

            val (opportunity, note) = notifier.reviewed.single()
            assertEquals("VETO", note.verdict)
            assertEquals("tariff ruling", note.risks.single().description)
            assertEquals("Reuters", note.sources.single().title)
            assertEquals(listOf("openai-gpt: APPROVE (0.80)"), note.otherReviewers)
            assertEquals(BigDecimal("240"), opportunity.shortLeg.contract.strike)
            assertEquals(BigDecimal("1.80"), opportunity.midCredit)
            assertTrue(notifier.plain.isEmpty())
            assertTrue(telegram.single().startsWith("⛔ AI VETO (0.80) — BULL_PUT NUE 240/230P"), telegram.single())
        }

    @Test
    fun `without a verdict in time the email goes out unavailable and the late verdict follows up`() =
        runTest {
            service.createTask(testSnapshot(asOf = morning))
            clock.now = morning.plus(Duration.ofMinutes(16))
            dispatcher.dispatch()

            val note = notifier.reviewed.single().second
            assertNull(note.verdict)
            assertTrue(note.unavailableReason!!.startsWith("no verdict from claude-opus after 16 min"), note.unavailableReason)
            assertTrue(telegram.isEmpty())

            answer("claude-opus", AiVerdict.APPROVE)
            dispatcher.dispatch()
            dispatcher.dispatch()
            assertEquals("APPROVE", notifier.followUps.single().verdict)
            assertEquals(1, telegram.size)
            assertEquals(1, notifier.reviewed.size)
        }

    @Test
    fun `a failed primary sends the email at once, naming the failure`() =
        runTest {
            val task = assertNotNull(service.createTask(testSnapshot(asOf = morning)))
            repeat(3) {
                val lease = service.claim("claude-opus", listOf("1"), null)!!.result.leaseToken!!
                service.reportFailure(task.id, "claude-opus", lease, AiFailureKind.TIMEOUT, "10 min")
            }
            dispatcher.dispatch()
            assertTrue(
                notifier.reviewed
                    .single()
                    .second.unavailableReason!!
                    .contains("failed (TIMEOUT"),
            )
        }

    @Test
    fun `the email is never held past the task's decision`() =
        runTest {
            val late =
                LocalDate
                    .of(2026, 11, 19)
                    .atTime(12, 55)
                    .atZone(ny)
                    .toInstant()
            clock.now = late
            service.createTask(testSnapshot(asOf = late))
            clock.now = late.plus(Duration.ofMinutes(5))
            dispatcher.dispatch()
            assertEquals(1, notifier.reviewed.size, "13:00 decision reached after 5 min, not 15")
        }

    @Test
    fun `the requester emails at once when no review is possible, and leaves reviewable titles to the dispatcher`() =
        runTest {
            val opportunity = mockk<SpreadOpportunity>(relaxed = true)
            val builder = mockk<ReviewSnapshotBuilder>()
            coEvery { builder.build(opportunity) } returns testSnapshot(asOf = morning)

            fun requester(cfg: AiReviewConfig) =
                AiCandidateReviewRequester(cfg, builder, AiReviewService(store, cfg, clock), notifier, this)

            requester(config.copy(enabled = false)).requestReview(opportunity)
            advanceUntilIdle()
            assertEquals(1, notifier.plain.size, "review off: plain email as before")

            requester(config).requestReview(opportunity)
            advanceUntilIdle()
            assertEquals(1, notifier.plain.size, "reviewable: the dispatcher emails it with the verdict")

            requester(config).requestReview(opportunity)
            advanceUntilIdle()
            assertEquals(1, notifier.plain.size, "same title again today: no second email")

            val afterLast =
                LocalDate
                    .of(2026, 11, 19)
                    .atTime(15, 20)
                    .atZone(ny)
                    .toInstant()
            coEvery { builder.build(opportunity) } returns testSnapshot("MPC", afterLast)
            requester(config).requestReview(opportunity)
            advanceUntilIdle()
            assertEquals(2, notifier.plain.size, "after the last decision: SKIPPED, emailed at once")
            assertNotNull(
                store.tasks.values
                    .single { it.symbol.value == "MPC" }
                    .emailedAt,
            )
        }

    @Test
    fun `the watchdog alerts once per change of reviewer health`() =
        runTest {
            val sent = mutableListOf<Pair<AlertLevel, String>>()
            val alerts =
                object : AlertPort {
                    override suspend fun send(
                        level: AlertLevel,
                        title: String,
                        body: String,
                    ) {
                        sent += level to title
                    }
                }
            val watchdog = AiReviewerWatchdog(service, config, alerts, clock)
            service.heartbeat("claude-opus", AiReviewerState.READY, null, "anthropic", "opus", "0.1")
            service.heartbeat("openai-gpt", AiReviewerState.READY, null, "openai", "gpt", "0.1")

            watchdog.check()
            assertTrue(sent.isEmpty(), "healthy at start: no message")

            service.heartbeat("claude-opus", AiReviewerState.AUTH_FAILED, "Please run /login", "anthropic", "opus", "0.1")
            watchdog.check()
            watchdog.check()
            assertEquals(listOf(AlertLevel.CRITICAL to "AI reviewer claude-opus logged out"), sent)

            clock.now = morning.plus(Duration.ofMinutes(4)) // nobody heartbeats any more
            watchdog.check()
            assertEquals(
                setOf("AI reviewer claude-opus silent", "AI reviewer openai-gpt silent"),
                sent.drop(1).map { it.second }.toSet(),
            )

            service.heartbeat("claude-opus", AiReviewerState.READY, null, "anthropic", "opus", "0.1")
            watchdog.check()
            assertEquals(AlertLevel.INFO to "AI reviewer claude-opus back", sent.last())
        }
}
