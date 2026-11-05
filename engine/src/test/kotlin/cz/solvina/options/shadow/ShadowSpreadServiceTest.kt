package cz.solvina.options.shadow

import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

/** NUE 250/240P Dec18 as the running example. */
class ShadowSpreadServiceTest {
    private val nue = Symbol("NUE")
    private val expiry = LocalDate.of(2026, 12, 18)
    private val sold = OptionContract(nue, expiry, BigDecimal("250"), OptionType.PUT)
    private val bought = OptionContract(nue, expiry, BigDecimal("240"), OptionType.PUT)

    private class InMemoryPort : ShadowSpreadPort {
        val rows = linkedMapOf<UUID, ShadowSpread>()

        override suspend fun findOpen() = rows.values.filter { it.status == ShadowStatus.OPEN }

        override suspend fun findAll() = rows.values.toList()

        override suspend fun save(shadow: ShadowSpread) = shadow.also { rows[it.id] = it }
    }

    private val port = InMemoryPort()
    private val market = mockk<MarketDataPort>()
    private val universe = mockk<UniversePort>().also { every { it.isMarketOpen(any()) } returns true }
    private val params = StrategyParamsRegistry(listOf(BullPutScannerConfig(timeProfitDte = 21), BearCallScannerConfig()))

    private fun service(today: LocalDate) =
        ShadowSpreadService(port, market, universe, params, Clock.fixed(today.atTime(20, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC))

    private val request =
        TradeExecutionRequest(
            soldContract = sold,
            boughtContract = bought,
            underlyingSymbol = nue,
            strategyId = StrategyId.BULL_PUT,
            targetCredit = BigDecimal("1.93"),
            floorCredit = BigDecimal("1.20"),
            maxRiskPerShare = BigDecimal("8.07"),
            ivRankAtEntry = 54.7,
            soldBid = BigDecimal("4.60"),
            soldAsk = BigDecimal("5.30"),
            boughtBid = BigDecimal("2.65"),
            boughtAsk = BigDecimal("3.40"),
            boughtMid = BigDecimal("3.025"),
            underlyingPriceAtEntry = BigDecimal("275"),
        )

    private fun quotes(
        spot: String,
        soldMid: String?,
        boughtMid: String?,
    ) {
        coEvery { market.getUnderlyingPrice(nue) } returns Money(BigDecimal(spot))
        coEvery { market.getOptionMidLive(sold) } returns soldMid?.let { Money(BigDecimal(it)) }
        coEvery { market.getOptionMidLive(bought) } returns boughtMid?.let { Money(BigDecimal(it)) }
    }

    @Test
    fun `an unfilled attempt is recorded once at mid, repeats and fills are not`() =
        runTest {
            val s = service(LocalDate.of(2026, 10, 30))
            s.record(request, ExecutionOutcome.FLOOR_REACHED)
            s.record(request, ExecutionOutcome.FLOOR_REACHED)
            s.record(request.copy(soldContract = sold.copy(strike = BigDecimal("245"))), ExecutionOutcome.FILLED)

            val only = port.rows.values.single()
            assertEquals(BigDecimal("1.93"), only.midCredit)
            assertEquals(BigDecimal("1.20"), only.naturalCredit)
        }

    @Test
    fun `a title is counted once while open, whatever its strikes or direction`() =
        runTest {
            val s = service(LocalDate.of(2026, 10, 30))
            s.record(request, ExecutionOutcome.FLOOR_REACHED)
            s.record(
                request.copy(
                    soldContract = sold.copy(strike = BigDecimal("245")),
                    boughtContract = bought.copy(strike = BigDecimal("235")),
                ),
                ExecutionOutcome.FLOOR_REACHED,
            )
            s.record(request.copy(strategyId = StrategyId.BEAR_CALL), ExecutionOutcome.TIMED_OUT)

            assertEquals(1, port.rows.size)
        }

    private fun candidate(
        symbol: String,
        score: Double,
        maxRisk: String = "4.00",
    ): ShadowCandidate {
        val s = Symbol(symbol)
        return ShadowCandidate(
            request.copy(
                underlyingSymbol = s,
                soldContract = sold.copy(symbol = s),
                boughtContract = bought.copy(symbol = s),
                maxRiskPerShare = BigDecimal(maxRisk),
            ),
            score,
            Instant.parse("2026-11-05T16:00:00Z"),
        )
    }

    @Test
    fun `a decision records every title ranked and selects only what fits the portfolio limits`() =
        runTest {
            service(LocalDate.of(2026, 11, 5)).recordDecision(
                listOf(candidate("CME", 0.9, maxRisk = "7.65"), candidate("GILD", 0.8), candidate("XOM", 0.7)),
            )

            val rows = port.rows.values.associateBy { it.symbol.value }
            assertEquals(listOf(1, 2, 3), listOf("CME", "GILD", "XOM").map { rows.getValue(it).decisionRank })
            assertEquals(false, rows.getValue("CME").selected, "max loss $765 is over the $500 limit")
            assertEquals(true, rows.getValue("GILD").selected, "best that fits")
            assertEquals(false, rows.getValue("XOM").selected, "only one new position per day")
        }

    @Test
    fun `the top-up decision respects the open portfolio limit and titles already held`() =
        runTest {
            val first = service(LocalDate.of(2026, 11, 5))
            first.recordDecision(listOf(candidate("GILD", 0.8)))
            first.recordDecision(listOf(candidate("GILD", 0.9), candidate("XOM", 0.7)))
            assertEquals(2, port.rows.size, "GILD is not recorded twice")
            assertEquals(
                false,
                port.rows.values
                    .single { it.symbol.value == "XOM" }
                    .selected,
                "today's slot is taken",
            )

            val nextDays = listOf(6, 9, 10).map { LocalDate.of(2026, 11, it) }
            nextDays.forEachIndexed { i, d -> service(d).recordDecision(listOf(candidate("T$i", 0.8))) }
            assertEquals(3, port.rows.values.count { it.selected }, "three open is the limit")
        }

    @Test
    fun `take-profit closes at half the mid credit`() =
        runTest {
            service(LocalDate.of(2026, 10, 30)).record(request, ExecutionOutcome.TIMED_OUT)
            quotes(spot = "285", soldMid = "1.40", boughtMid = "0.50") // value 0.90 ≤ 0.965

            service(LocalDate.of(2026, 11, 10)).revalueOpenMarkets()

            val row = port.rows.values.single()
            assertEquals(ShadowStatus.CLOSED_PROFIT, row.status)
            assertEquals(0, BigDecimal("0.90").compareTo(row.exitValue))
        }

    @Test
    fun `the underlying stop closes at the current value when spot is through the short strike`() =
        runTest {
            service(LocalDate.of(2026, 10, 30)).record(request, ExecutionOutcome.FLOOR_REACHED)
            quotes(spot = "249.50", soldMid = "8.00", boughtMid = "4.10")

            service(LocalDate.of(2026, 11, 10)).revalueOpenMarkets()

            assertEquals(
                ShadowStatus.CLOSED_STOP,
                port.rows.values
                    .single()
                    .status,
            )
        }

    @Test
    fun `the time exit closes at 21 days to expiry and no quote keeps it open`() =
        runTest {
            service(LocalDate.of(2026, 10, 30)).record(request, ExecutionOutcome.FLOOR_REACHED)
            quotes(spot = "270", soldMid = null, boughtMid = null)
            service(LocalDate.of(2026, 11, 27)).revalueOpenMarkets()
            assertEquals(
                ShadowStatus.OPEN,
                port.rows.values
                    .single()
                    .status,
                "no quote → wait",
            )

            quotes(spot = "270", soldMid = "1.60", boughtMid = "0.40")
            service(LocalDate.of(2026, 11, 27)).revalueOpenMarkets()
            assertEquals(
                ShadowStatus.CLOSED_TIME,
                port.rows.values
                    .single()
                    .status,
            )
        }

    @Test
    fun `a price request that never answers times out without blocking the next position`() =
        runTest {
            // 2026-11-02: one ORCL request never answered and held the whole pass for 15 minutes.
            val orcl = Symbol("ORCL")
            service(LocalDate.of(2026, 10, 30)).record(
                request.copy(
                    underlyingSymbol = orcl,
                    soldContract = sold.copy(symbol = orcl),
                    boughtContract = bought.copy(symbol = orcl),
                ),
                ExecutionOutcome.FLOOR_REACHED,
            )
            service(LocalDate.of(2026, 10, 30)).record(request, ExecutionOutcome.FLOOR_REACHED)
            coEvery { market.getUnderlyingPrice(orcl) } coAnswers { awaitCancellation() }
            quotes(spot = "285", soldMid = "1.40", boughtMid = "0.50")

            service(LocalDate.of(2026, 11, 10)).revalueOpenMarkets()

            assertEquals(
                ShadowStatus.CLOSED_PROFIT,
                port.rows.values
                    .single { it.symbol == nue }
                    .status,
            )
            assertEquals(
                ShadowStatus.OPEN,
                port.rows.values
                    .single { it.symbol == orcl }
                    .status,
            )
        }

    @Test
    fun `an expired shadow settles at intrinsic value`() =
        runTest {
            service(LocalDate.of(2026, 10, 30)).record(request, ExecutionOutcome.FLOOR_REACHED)
            val noStop =
                StrategyParamsRegistry(
                    listOf(BullPutScannerConfig(underlyingStopEnabled = false, timeProfitDte = 0), BearCallScannerConfig()),
                )
            quotes(spot = "245", soldMid = null, boughtMid = null)

            ShadowSpreadService(port, market, universe, noStop, Clock.fixed(expiry.atTime(21, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC))
                .revalueOpenMarkets()

            val row = port.rows.values.single()
            assertEquals(ShadowStatus.EXPIRED, row.status)
            assertEquals(0, BigDecimal("5").compareTo(row.exitValue)) // 250 − 245, long 240 worthless
        }
}
