package cz.solvina.options.aireview

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import cz.solvina.options.domain.features.aireview.ReviewSnapshotBuilder
import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.news.NewsConfig
import cz.solvina.options.domain.features.news.NewsHeadline
import cz.solvina.options.domain.features.news.NewsPort
import cz.solvina.options.domain.features.notification.SpreadOpportunity
import cz.solvina.options.domain.features.regime.DirectionalBias
import cz.solvina.options.domain.features.regime.RegimeSignal
import cz.solvina.options.domain.features.regime.TrendRegime
import cz.solvina.options.domain.features.regime.TrendRegimeService
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.scanner.BullPutScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.features.scanner.ScannerPoolConfig
import cz.solvina.options.domain.features.shadow.ShadowSpreadPort
import cz.solvina.options.domain.features.spread.SpreadQueryFacade
import cz.solvina.options.domain.features.spread.model.StrategyId
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.features.volatility.IvRankStorePort
import cz.solvina.options.domain.features.volatility.IvSummary
import cz.solvina.options.domain.features.volatility.StoredIvRank
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.IvRank
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionGreeks
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import cz.solvina.options.testutil.InMemoryAiReviewStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewSnapshotBuilderTest {
    private val nue = Symbol("NUE")
    private val expiry = LocalDate.of(2026, 12, 18)

    private fun quote(
        strike: String,
        delta: Double,
        estimated: Boolean = false,
    ) = OptionQuote(
        contract = OptionContract(nue, expiry, BigDecimal(strike), OptionType.PUT),
        bid = Money(BigDecimal("1.00")),
        ask = Money(BigDecimal("1.20")),
        mid = Money(BigDecimal("1.10")),
        greeks = OptionGreeks(delta = delta, gamma = 0.01, theta = -0.02, vega = 0.1, iv = 0.31),
        greeksEstimated = estimated,
    )

    private val opportunity =
        SpreadOpportunity(
            strategyId = StrategyId.BULL_PUT,
            symbol = nue,
            session = "US",
            detectedAt = Instant.parse("2026-11-19T15:30:00Z"),
            ivRank = 62.0,
            warnings = listOf("IV rank extreme"),
            ivRankThreshold = 45.0,
            expiry = expiry,
            dte = 29,
            minDte = 30,
            maxDte = 45,
            preferredDte = 35,
            targetDelta = 0.25,
            deltaMin = 0.15,
            deltaMax = 0.35,
            underlyingPrice = BigDecimal("251.00"),
            shortLeg = quote("240", -0.24),
            // IBKR sent no greeks and the estimator failed: delta is NaN.
            longLeg = quote("230", Double.NaN, estimated = true),
            midCredit = BigDecimal("1.80"),
            bidCredit = BigDecimal("1.70"),
            minCreditPerShare = BigDecimal("0.90"),
            targetWidth = BigDecimal("5"),
            actualWidth = BigDecimal("10"),
            maxRiskPerShare = BigDecimal("8.20"),
            totalCapital = BigDecimal("20000"),
            maxRiskPercent = 0.025,
            quantity = 1,
            takeProfitPercent = 0.5,
            stopLossPercent = 2.0,
            underlyingStopEnabled = true,
            underlyingStopBufferPct = 0.0,
            timeProfitDte = 21,
            driftProtectionPct = 0.0,
        )

    private val regime = mockk<TrendRegimeService>()
    private val volatility = mockk<VolatilityPort>()
    private val ivStore = mockk<IvRankStorePort>()
    private val universe = mockk<UniversePort>()
    private val spreadQuery = mockk<SpreadQueryFacade>()
    private val shadows = mockk<ShadowSpreadPort>()

    private fun builder(news: NewsPort? = null) =
        ReviewSnapshotBuilder(
            regime,
            volatility,
            ivStore,
            universe,
            spreadQuery,
            shadows,
            InMemoryAiReviewStore(),
            ScannerConfig(),
            BullPutScannerConfig(),
            BearCallScannerConfig(),
            ScannerPoolConfig(),
            NewsConfig(maxArticles = 1, maxArticleChars = 10),
            news,
        )

    private fun wireHappyPath() {
        every { regime.regimeFor(nue) } returns
            RegimeSignal(
                TrendRegime.UPTREND,
                BigDecimal("250"),
                BigDecimal("240"),
                BigDecimal("230"),
                BigDecimal("58"),
                DirectionalBias.BULLISH,
                2.5,
            )
        coEvery { universe.get(nue) } returns InstrumentConfig(nue, nextEarningsDate = LocalDate.of(2027, 1, 27))
        every { universe.sectorOf(any()) } returns "Materials"
        coEvery { spreadQuery.symbolsWithActiveSpread() } returns setOf(Symbol("MPC"))
        coEvery { spreadQuery.activeSpreadCount() } returns 1
        coEvery { shadows.findAll() } returns emptyList()
        every { ivStore.find(nue) } returns
            StoredIvRank(62.0, 0.34, Instant.EPOCH, IvSummary(0.22, 0.41, 71.0, 250, listOf(0.30, Double.NaN, 0.34)))
    }

    @Test
    fun `NaN greeks become null and the snapshot serializes to valid JSON`() =
        runTest {
            wireHappyPath()
            coEvery { volatility.getIvRank(nue) } returns IvRank(62.0, 0.34, Instant.EPOCH)

            val snapshot = builder().build(opportunity)

            assertEquals(0.22, snapshot.iv?.historyMin)
            assertEquals(71.0, snapshot.iv?.percentile)
            assertEquals(listOf(0.30, 0.34), snapshot.iv?.recent30)
            assertNull(snapshot.candidate.longLeg.delta)
            assertTrue(snapshot.candidate.longLeg.greeksEstimated)
            assertEquals(-0.24, snapshot.candidate.shortLeg.delta)
            assertEquals(LocalDate.of(2026, 11, 19), snapshot.tradingDate)
            assertEquals(false, snapshot.calendar?.earningsBeforeExpiry)
            assertEquals("Materials", snapshot.portfolio?.candidateSector)
            assertNotNull(snapshot.rulesVersion)

            val mapper = jacksonObjectMapper().registerModule(JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            val json = mapper.writeValueAsString(snapshot)
            assertFalse(json.contains("NaN"), json)
            mapper.readTree(json) // strict parse: would throw on NaN tokens
        }

    @Test
    fun `an earnings date already past is reported as the last report, not as earnings before expiry`() =
        runTest {
            wireHappyPath()
            coEvery { universe.get(nue) } returns InstrumentConfig(nue, nextEarningsDate = LocalDate.of(2026, 11, 3))
            coEvery { volatility.getIvRank(nue) } returns IvRank(62.0, 0.34, Instant.EPOCH)

            val calendar = assertNotNull(builder().build(opportunity).calendar)

            assertNull(calendar.nextEarningsDate)
            assertNull(calendar.earningsBeforeExpiry)
            assertEquals(LocalDate.of(2026, 11, 3), calendar.lastEarningsDate)
        }

    @Test
    fun `a failing source leaves only its own section empty`() =
        runTest {
            wireHappyPath()
            coEvery { volatility.getIvRank(nue) } throws IllegalStateException("No IV data")
            coEvery { spreadQuery.activeSpreadCount() } throws RuntimeException("db down")

            val snapshot = builder().build(opportunity)

            assertNull(snapshot.iv)
            assertNull(snapshot.portfolio)
            assertNotNull(snapshot.trend)
            assertNotNull(snapshot.calendar)
            assertEquals(BigDecimal("240"), snapshot.candidate.shortLeg.strike)
        }

    @Test
    fun `news carries headlines newest first with a body for the newest only, and a failing feed leaves it empty`() =
        runTest {
            wireHappyPath()
            coEvery { volatility.getIvRank(nue) } returns IvRank(62.0, 0.34, Instant.EPOCH)
            val news = mockk<NewsPort>()
            coEvery { news.headlines(nue, any(), 25) } returns
                listOf(
                    NewsHeadline(Instant.parse("2026-11-19T13:00:00Z"), "BRFUPDN", "a1", "NUE downgraded at XYZ"),
                    NewsHeadline(Instant.parse("2026-11-18T13:00:00Z"), "BRFG", "a2", "Steel stocks slide"),
                )
            coEvery { news.article("BRFUPDN", "a1") } returns "XYZ cut its target to 150 on weaker pricing."

            val section = assertNotNull(builder(news).build(opportunity).news)
            assertEquals(listOf("NUE downgraded at XYZ", "Steel stocks slide"), section.headlines.map { it.headline })
            assertEquals("XYZ cut it…", section.headlines[0].body)
            assertNull(section.headlines[1].body)
            assertEquals(listOf("BRFG", "BRFUPDN"), section.providers)

            coEvery { news.headlines(nue, any(), 25) } throws IllegalStateException("no conId")
            assertNull(builder(news).build(opportunity).news)
        }
}
