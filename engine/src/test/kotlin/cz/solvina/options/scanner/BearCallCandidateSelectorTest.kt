package cz.solvina.options.scanner

import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.market.OptionChainPort
import cz.solvina.options.domain.features.market.model.OptionQuote
import cz.solvina.options.domain.features.scanner.BearCallCandidateSelector
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.MarketSchedule
import cz.solvina.options.domain.features.universe.UniversePort
import cz.solvina.options.domain.features.volatility.VolatilityPort
import cz.solvina.options.domain.models.IvRank
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionGreeks
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Entry filter pipeline for bear call spreads — the mirror of the bull put selector for CALLs:
 * SELL the short call at the delta target (lower strike), BUY the long call at soldStrike + width
 * (higher strike). Credit/risk math is identical (real quoted prices; no skew adjustment).
 */
class BearCallCandidateSelectorTest {
    private val symbol = Symbol("SPY")
    private val today = LocalDate.of(2025, 1, 15)
    private val clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
    private val expiry38d = today.plusDays(38)

    // Underlying $500; short call (lower strike) at $520 ~0.29 delta, long call (higher) at $525.
    private val soldStrike = BigDecimal("520")
    private val boughtStrike = BigDecimal("525")

    private val soldContract = OptionContract(symbol, expiry38d, soldStrike, OptionType.CALL)
    private val boughtContract = OptionContract(symbol, expiry38d, boughtStrike, OptionType.CALL)

    // sold mid $1.50, bought mid $0.70 → credit $0.80; width $5 → maxRisk $4.20.
    private val validChain =
        listOf(
            call(strike = soldStrike, delta = 0.29, bid = 1.40, ask = 1.60),
            call(strike = boughtStrike, delta = 0.22, bid = 0.65, ask = 0.75),
        )

    private val capitalOf50k = Money(BigDecimal("50000"))

    @Test
    fun `no candidate when IV rank is below the threshold`() =
        runTest {
            // 40 % < default bear-call threshold 45 %.
            val result = buildSelector(ivRank = 40.0).select(symbol, capitalOf50k)
            assertNull(result, "Low IV Rank must prevent entry")
        }

    @Test
    fun `no candidate when no call has delta within the tolerance band`() =
        runTest {
            val chain =
                listOf(
                    call(strike = soldStrike, delta = 0.10, bid = 0.20, ask = 0.30), // too small
                    call(strike = boughtStrike, delta = 0.45, bid = 1.80, ask = 2.00), // too large
                )
            val result = buildSelector(chain = chain).select(symbol, capitalOf50k)
            assertNull(result, "No qualifying call delta must prevent entry")
        }

    @Test
    fun `no candidate when mid credit is below minCreditPerShare`() =
        runTest {
            // sold mid $0.25, bought mid $0.10 → credit $0.15 < default min $0.40.
            val cheapChain =
                listOf(
                    call(strike = soldStrike, delta = 0.29, bid = 0.20, ask = 0.30),
                    call(strike = boughtStrike, delta = 0.22, bid = 0.05, ask = 0.15),
                )
            val result = buildSelector(chain = cheapChain).select(symbol, capitalOf50k)
            assertNull(result, "Insufficient credit must prevent entry")
        }

    @Test
    fun `no candidate when position risk exceeds the capital allocation limit`() =
        runTest {
            // maxRiskPerContract $420 > allowed ($1000 × 2.5 % = $25).
            val result = buildSelector().select(symbol, Money(BigDecimal("1000")))
            assertNull(result, "Position risk exceeding capital limit must prevent entry")
        }

    @Test
    fun `happy path sells the lower-strike call, buys the higher, and computes credit and risk`() =
        runTest {
            val result = buildSelector().select(symbol, capitalOf50k)

            assertNotNull(result)
            assertEquals(soldContract, result.soldContract)
            assertEquals(boughtContract, result.boughtContract)
            assertEquals(OptionType.CALL, result.soldContract.type)
            assertEquals(OptionType.CALL, result.boughtContract.type)
            // short strike is below the long strike for a bear call
            assertEquals(true, result.soldContract.strike < result.boughtContract.strike)
            // credit = soldMid($1.50) − boughtMid($0.70) = $0.80
            assertEquals(0, BigDecimal("0.8000").compareTo(result.targetCredit))
            // maxRiskPerShare = width($5.00) − credit($0.80) = $4.20
            assertEquals(0, BigDecimal("4.2000").compareTo(result.maxRiskPerShare))
        }

    @Test
    fun `no candidate when ex-dividend is within the entry buffer for a US name`() =
        runTest {
            // ex-div tomorrow (buffer = 48h → 2 days), US session → entry blocked.
            val universePort =
                mockk<UniversePort>(relaxed = true) {
                    coEvery { get(symbol) } returns InstrumentConfig(symbol = symbol, exDividendDate = today.plusDays(1))
                    every { getMarketSchedule(symbol) } returns
                        MarketSchedule(ZoneId.of("America/New_York"), LocalTime.of(9, 30), LocalTime.of(16, 0), "US")
                }

            val result = buildSelector(universePort = universePort).select(symbol, capitalOf50k)

            assertNull(result, "Imminent ex-dividend must block bear-call entry")
        }

    private fun buildSelector(
        ivRank: Double = 50.0,
        expirations: Set<LocalDate> = setOf(expiry38d),
        chain: List<OptionQuote> = validChain,
        config: BearCallScannerConfig = BearCallScannerConfig(),
        universePort: UniversePort = mockk(relaxed = true),
    ) = BearCallCandidateSelector(
        volatilityPort =
            object : VolatilityPort {
                override suspend fun getIvRank(symbol: Symbol) = IvRank(rank = ivRank, currentIv = 0.25, calculatedAt = Instant.now())
            },
        marketDataPort =
            object : MarketDataPort {
                override suspend fun getUnderlyingPrice(symbol: Symbol) = Money(BigDecimal("500"))

                override suspend fun getOptionMid(contract: OptionContract) = Money(BigDecimal.ZERO)
            },
        optionChainPort =
            object : OptionChainPort {
                override suspend fun getAvailableExpirations(symbol: Symbol) = expirations

                override suspend fun getOptionChain(
                    symbol: Symbol,
                    expiry: LocalDate,
                    underlyingPrice: Money,
                ) = chain.filter { it.contract.expiry == expiry }
            },
        universePort = universePort,
        config = config,
        clock = clock,
    )

    private fun call(
        strike: BigDecimal,
        delta: Double,
        bid: Double,
        ask: Double,
        expiry: LocalDate = expiry38d,
    ): OptionQuote {
        val mid = (bid + ask) / 2
        return OptionQuote(
            contract = OptionContract(symbol, expiry, strike, OptionType.CALL),
            bid = Money(BigDecimal.valueOf(bid)),
            ask = Money(BigDecimal.valueOf(ask)),
            mid = Money(BigDecimal.valueOf(mid)),
            greeks = OptionGreeks(delta = delta, gamma = 0.01, theta = -0.02, vega = 0.10, iv = 0.25),
        )
    }
}
