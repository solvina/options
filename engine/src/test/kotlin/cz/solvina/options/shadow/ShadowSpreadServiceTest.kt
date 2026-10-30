package cz.solvina.options.shadow

import cz.solvina.options.domain.features.execution.model.ExecutionOutcome
import cz.solvina.options.domain.features.execution.model.TradeExecutionRequest
import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.scanner.BullPutScannerConfig
import cz.solvina.options.domain.features.scanner.StrategyParamsRegistry
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
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
