package cz.solvina.options.shadow

import cz.solvina.options.aireview.testSnapshot
import cz.solvina.options.domain.features.aireview.AiReviewConfig
import cz.solvina.options.domain.features.aireview.AiReviewReportService
import cz.solvina.options.domain.features.aireview.AiReviewService
import cz.solvina.options.domain.features.aireview.AiVerdict
import cz.solvina.options.domain.features.aireview.ResultSubmission
import cz.solvina.options.domain.features.aireview.ReviewerConfig
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.scanner.BullPutScannerConfig
import cz.solvina.options.domain.features.scanner.StrategyParamsRegistry
import cz.solvina.options.domain.features.shadow.ShadowCandidate
import cz.solvina.options.domain.features.shadow.ShadowSpread
import cz.solvina.options.domain.features.shadow.ShadowSpreadPort
import cz.solvina.options.domain.features.shadow.ShadowSpreadService
import cz.solvina.options.domain.features.shadow.ShadowStatus
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import cz.solvina.options.testutil.InMemoryAiReviewStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AI review D6: the AI layer at the pool decision, the per-task decision record, marks and the report. */
class AiLayerTest {
    private val ny = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 11, 19)
    private val morning = day.atTime(10, 30).atZone(ny).toInstant()
    private val decision = day.atTime(13, 0).atZone(ny).toInstant()
    private val expiry = LocalDate.of(2026, 12, 18)

    private class SettableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private class InMemoryPort : ShadowSpreadPort {
        val rows = linkedMapOf<UUID, ShadowSpread>()
        val marks = mutableListOf<Pair<UUID, BigDecimal?>>()

        override suspend fun findOpen() = rows.values.filter { it.status == ShadowStatus.OPEN }

        override suspend fun findAll() = rows.values.toList()

        override suspend fun save(shadow: ShadowSpread) = shadow.also { rows[it.id] = it }

        override suspend fun saveMark(
            shadowId: UUID,
            valuedAt: Instant,
            value: BigDecimal?,
            underlying: BigDecimal?,
            status: ShadowStatus,
        ) {
            marks += shadowId to value
        }
    }

    private val clock = SettableClock(morning)
    private val store = InMemoryAiReviewStore()
    private val config = AiReviewConfig(enabled = true, reviewers = listOf(ReviewerConfig("claude-opus", primary = true)))
    private val aiReview = AiReviewService(store, config, clock)
    private val port = InMemoryPort()
    private val market = mockk<MarketDataPort>()
    private val universe =
        mockk<UniversePort>().also {
            every { it.isMarketOpen(any()) } returns true
            every { it.sectorOf(any()) } returns null
        }
    private val params = StrategyParamsRegistry(listOf(BullPutScannerConfig(timeProfitDte = 21), BearCallScannerConfig()))
    private val shadows = ShadowSpreadService(port, market, universe, params, clock, aiReview = aiReview)

    private fun candidate(
        symbol: String,
        score: Double,
        strategy: StrategyId = StrategyId.BULL_PUT,
    ): ShadowCandidate {
        val s = Symbol(symbol)
        return ShadowCandidate(
            TradeExecutionRequest(
                soldContract = OptionContract(s, expiry, BigDecimal("240"), OptionType.PUT),
                boughtContract = OptionContract(s, expiry, BigDecimal("235"), OptionType.PUT),
                underlyingSymbol = s,
                strategyId = strategy,
                targetCredit = BigDecimal("1.00"),
                floorCredit = BigDecimal("0.80"),
                maxRiskPerShare = BigDecimal("4.00"),
                ivRankAtEntry = 60.0,
                soldBid = BigDecimal("2.00"),
                soldAsk = BigDecimal("2.20"),
                boughtBid = BigDecimal("1.00"),
                boughtAsk = BigDecimal("1.20"),
                boughtMid = BigDecimal("1.10"),
                underlyingPriceAtEntry = BigDecimal("260"),
            ),
            score,
            morning,
        )
    }

    private suspend fun review(
        symbol: String,
        verdict: AiVerdict?,
    ) {
        aiReview.createTask(testSnapshot(symbol, morning))
        if (verdict == null) return
        val claimed = assertNotNull(aiReview.claim("claude-opus", listOf("1"), null))
        aiReview.submitResult(
            claimed.task.id,
            ResultSubmission("claude-opus", claimed.result.leaseToken!!, "1", verdict, 0.7, "reason $symbol"),
        )
    }

    @Test
    fun `a veto removes the top pick from the AI portfolio only, and the next title takes its slot`() =
        runTest {
            review("GILD", AiVerdict.VETO)
            review("XOM", AiVerdict.APPROVE)
            review("MPC", null) // reviewed, but stops qualifying before the decision
            review("WELL", null) // no verdict in time
            clock.now = decision

            shadows.recordDecision(listOf(candidate("GILD", 0.9), candidate("XOM", 0.8), candidate("WELL", 0.7), candidate("CME", 0.6)))

            val rows = port.rows.values.associateBy { it.symbol.value }
            assertEquals(true to false, rows.getValue("GILD").let { it.selected to it.aiSelected })
            assertEquals(false to true, rows.getValue("XOM").let { it.selected to it.aiSelected }, "the slot the veto freed")
            assertEquals("VETO", rows.getValue("GILD").aiVerdict)
            assertNull(rows.getValue("WELL").aiVerdict, "no verdict in time")
            assertNotNull(rows.getValue("WELL").aiTaskId)
            assertNull(rows.getValue("CME").aiTaskId, "never reviewed")

            val decisions = store.tasks.values.associate { it.symbol.value to it.decision }
            assertEquals("RECORDED", decisions.getValue("GILD")!!.action)
            assertEquals(true, decisions.getValue("GILD")!!.wouldGate)
            assertEquals(rows.getValue("GILD").id, decisions.getValue("GILD")!!.shadowSpreadId)
            assertEquals(false, decisions.getValue("XOM")!!.wouldGate)
            assertEquals("DROPPED", decisions.getValue("MPC")!!.action)
        }

    @Test
    fun `the AI portfolio keeps its own counters across decisions`() =
        runTest {
            review("GILD", AiVerdict.VETO)
            clock.now = decision
            shadows.recordDecision(listOf(candidate("GILD", 0.9), candidate("XOM", 0.8)))

            clock.now = day.atTime(15, 0).atZone(ny).toInstant()
            shadows.recordDecision(listOf(candidate("CME", 0.9)))

            val rows = port.rows.values.associateBy { it.symbol.value }
            assertEquals(false, rows.getValue("CME").selected, "rules portfolio already took GILD today")
            assertEquals(false, rows.getValue("CME").aiSelected, "AI portfolio already took XOM today")
        }

    @Test
    fun `a title that re-qualified as the other strategy does not carry the verdict`() =
        runTest {
            review("GILD", AiVerdict.VETO)
            clock.now = decision
            shadows.recordDecision(listOf(candidate("GILD", 0.9, StrategyId.BEAR_CALL)))

            assertEquals(
                true,
                port.rows.values
                    .single()
                    .aiSelected,
            )
            assertEquals(
                "STRATEGY_CHANGED",
                store.tasks.values
                    .single()
                    .decision!!
                    .action,
            )
        }

    @Test
    fun `each revaluation leaves a mark`() =
        runTest {
            clock.now = decision
            shadows.recordDecision(listOf(candidate("XOM", 0.9)))
            val s = Symbol("XOM")
            coEvery { market.getUnderlyingPrice(s) } returns Money(BigDecimal("262"))
            coEvery { market.getOptionMidLive(any()) } answers {
                Money(if (firstArg<OptionContract>().strike == BigDecimal("240")) BigDecimal("1.50") else BigDecimal("0.70"))
            }
            shadows.revalueOpenMarkets()

            assertEquals(
                listOf<Pair<UUID, BigDecimal?>>(
                    port.rows.values
                        .single()
                        .id to BigDecimal("0.8000"),
                ),
                port.marks,
            )
        }

    @Test
    fun `the report groups would-be trades by verdict and scores the vetoes`() =
        runTest {
            fun row(
                symbol: String,
                verdict: String?,
                exit: String?,
                daysAgo: Long,
                selected: Boolean = false,
                aiSelected: Boolean = false,
            ) = ShadowSpread(
                strategyId = StrategyId.BULL_PUT,
                symbol = Symbol(symbol),
                optionType = OptionType.PUT,
                expiry = expiry,
                soldStrike = BigDecimal("240"),
                boughtStrike = BigDecimal("235"),
                openedAt = decision.minusSeconds(daysAgo * 86_400),
                sourceOutcome = "POOL_DECISION",
                midCredit = BigDecimal("1.00"),
                status = if (exit == null) ShadowStatus.OPEN else ShadowStatus.CLOSED_PROFIT,
                exitValue = exit?.let { BigDecimal(it) },
                lastValue = exit?.let { BigDecimal(it) } ?: BigDecimal("0.90"),
                score = 0.5,
                selected = selected,
                aiSelected = aiSelected,
                aiTaskId = verdict?.let { UUID.randomUUID() },
                aiVerdict = verdict?.takeIf { it != "LATE" },
                aiConfidence = 0.8,
            )
            listOf(
                row("A", "APPROVE", "0.50", 1, selected = true, aiSelected = true), // +50
                row("B", "APPROVE", "0.40", 2), // +60
                row("C", "VETO", "3.00", 1, selected = true), // −200: a loss avoided
                row("D", "VETO", "0.20", 2, aiSelected = true), // +80: a win forgone
                row("E", "LATE", "0.50", 3),
                row("F", null, null, 0),
            ).forEach { port.save(it) }

            val r = AiReviewReportService(port, store, config, clock).report()

            assertEquals(listOf("APPROVE", "VETO", "NO_VERDICT_IN_TIME", "NOT_REVIEWED"), r.cohorts.map { it.label })
            assertEquals(110.0, r.cohorts[0].realizedPnl)
            assertEquals(2, r.cohorts[1].closed)
            assertEquals(120.0, r.lossesAvoidedMinusWinsForgone, "200 avoided − 80 forgone")
            assertEquals(10.0, r.cohorts[3].markedPnl, "open, marked at 0.90")
            assertEquals(-150.0, r.rulesOnly.realizedPnl)
            assertEquals(130.0, r.withAi.realizedPnl)
            val d = assertNotNull(r.approveMinusVeto)
            assertEquals(55.0 - (-60.0), d.mean)
            assertNotNull(d.low)
            assertTrue(r.calibration.first { it.low == 0.75 }.closed == 4)
        }

    // -------------------------------------------------------------------------
    // Reviewed candidates: every title from first qualification, no veto applied
    // -------------------------------------------------------------------------

    @Test
    fun `a candidate is recorded once and never counts as held at the decision`() =
        runTest {
            shadows.recordCandidate(candidate("GILD", 0.9).request, 0.9, morning)
            shadows.recordCandidate(candidate("GILD", 0.9).request, 0.9, morning)
            assertEquals(1, port.rows.size, "one open candidate row per title")

            clock.now = decision
            shadows.recordDecision(listOf(candidate("GILD", 0.9)))

            val rows = port.rows.values.groupBy { it.sourceOutcome }
            assertEquals(1, rows.getValue(ShadowSpreadService.REVIEWED_CANDIDATE).size)
            val decided = rows.getValue("POOL_DECISION").single()
            assertTrue(decided.selected, "the candidate row must not block the rules portfolio")
            val c = rows.getValue(ShadowSpreadService.REVIEWED_CANDIDATE).single()
            assertTrue(!c.selected && !c.aiSelected, "candidate rows stay out of both portfolios")
        }

    @Test
    fun `the report scores every reviewed candidate by its verdict, apart from the decision rows`() =
        runTest {
            review("GILD", AiVerdict.VETO)
            review("XOM", AiVerdict.APPROVE)
            review("WELL", null) // no verdict in time
            listOf("GILD", "XOM", "WELL", "CME").forEach { shadows.recordCandidate(candidate(it, 0.5).request, 0.5, morning) }
            // GILD (vetoed) went on to lose 200, XOM (approved) won 50, WELL won 50, CME still open.
            val exits = mapOf("GILD" to "3.00", "XOM" to "0.50", "WELL" to "0.50")
            port.rows.values.toList().forEach { r ->
                exits[r.symbol.value]?.let {
                    port.save(r.copy(status = ShadowStatus.CLOSED_PROFIT, exitValue = BigDecimal(it), closedAt = decision))
                }
            }

            val r = AiReviewReportService(port, store, config, clock).report()

            assertTrue(r.cohorts.isEmpty(), "candidate rows are not decision rows")
            val byLabel = r.candidates.associateBy { it.label }
            assertEquals(setOf("APPROVE", "VETO", "NO_VERDICT_IN_TIME", "NOT_REVIEWED"), byLabel.keys)
            assertEquals(-200.0, byLabel.getValue("VETO").realizedPnl)
            assertEquals(50.0, byLabel.getValue("APPROVE").realizedPnl)
            assertEquals(1, byLabel.getValue("NOT_REVIEWED").open)
            assertEquals(200.0, r.candidateLossesAvoidedMinusWinsForgone, "the veto avoided a 200 loss")
            assertEquals(250.0, assertNotNull(r.candidateApproveMinusVeto).mean)
        }

    // -------------------------------------------------------------------------
    // Preferred strategy: the other spread the reviewer would rather sell
    // -------------------------------------------------------------------------

    private suspend fun reviewPreferring(
        symbol: String,
        preferred: String?,
    ) {
        aiReview.createTask(testSnapshot(symbol, morning))
        val claimed = assertNotNull(aiReview.claim("claude-opus", listOf("1"), null))
        aiReview.submitResult(
            claimed.task.id,
            ResultSubmission(
                "claude-opus",
                claimed.result.leaseToken!!,
                "1",
                AiVerdict.VETO,
                0.8,
                "falling knife",
                preferredStrategy = preferred,
            ),
        )
    }

    @Test
    fun `only a preference for the OTHER strategy is a switch`() =
        runTest {
            reviewPreferring("UNH", "BEAR_CALL")
            reviewPreferring("XOM", "BULL_PUT")
            reviewPreferring("CME", "NONE")
            reviewPreferring("GILD", null)
            val day = testSnapshot("UNH", morning).tradingDate

            assertEquals(StrategyId.BEAR_CALL, aiReview.preferredSwitch(day, Symbol("UNH")))
            listOf("XOM", "CME", "GILD", "WELL").forEach {
                assertNull(aiReview.preferredSwitch(day, Symbol(it)), it)
            }
        }

    @Test
    fun `an AI-preferred would-be trade stays out of the decision rows and has its own cohort`() =
        runTest {
            shadows.recordCandidate(candidate("UNH", 0.5, StrategyId.BEAR_CALL).request, 0.5, morning, ShadowSpreadService.AI_PREFERRED)
            assertTrue(shadows.hasOpen(Symbol("UNH"), ShadowSpreadService.AI_PREFERRED))

            clock.now = decision
            shadows.recordDecision(listOf(candidate("UNH", 0.9)))
            val decided = port.rows.values.single { it.sourceOutcome == "POOL_DECISION" }
            assertTrue(decided.selected, "an AI_PREFERRED row must not block the rules portfolio")

            val r = AiReviewReportService(port, store, config, clock).report()
            assertEquals(listOf("BEAR_CALL"), r.preferred.map { it.label })
            assertTrue(r.cohorts.flatMap { listOf(it.label) }.none { it == "AI_PREFERRED" })
        }
}
