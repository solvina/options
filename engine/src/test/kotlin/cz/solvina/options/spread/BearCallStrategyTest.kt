package cz.solvina.options.spread

import cz.solvina.options.domain.features.market.MarketDataPort
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.scanner.BearCallScannerConfig
import cz.solvina.options.domain.features.spread.model.BearCallSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.features.spread.strategy.bearcall.BearCallStrategy
import cz.solvina.options.domain.features.universe.InstrumentConfig
import cz.solvina.options.domain.features.universe.MarketSchedule
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Bear-call dividend assignment protection. Force-close only when ALL hold (US/American-style only):
 * ex-dividend within the window, short call ITM, and short-call extrinsic < dividend amount.
 */
class BearCallStrategyTest {
    private val symbol = Symbol("AAPL")
    private val expiry = LocalDate.of(2025, 8, 15)
    private val today = LocalDate.of(2025, 7, 30)
    private val clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
    private val config = BearCallScannerConfig() // dividend-check-window = 48h → 2 days

    // Short call 180 (ITM when spot > 180), long call 185.
    private fun spread() =
        BearCallSpread(
            id = UUID.randomUUID(),
            symbol = symbol,
            soldLeg =
                SpreadLeg(
                    OptionContract(symbol, expiry, BigDecimal("180"), OptionType.CALL),
                    LegAction.SELL,
                    Money(BigDecimal("1.50")),
                    1,
                ),
            boughtLeg =
                SpreadLeg(
                    OptionContract(symbol, expiry, BigDecimal("185"), OptionType.CALL),
                    LegAction.BUY,
                    Money(BigDecimal("0.50")),
                    2,
                ),
            creditPerShare = BigDecimal("1.00"),
            maxRiskPerShare = BigDecimal("4.00"),
            status = SpreadStatus.OPEN,
            ivRankAtEntry = 50.0,
            underlyingPriceAtEntry = BigDecimal("178"),
            openedAt = Instant.now(clock),
        )

    private fun strategy(
        session: String = "US",
        exDiv: LocalDate? = today.plusDays(1),
        dividend: BigDecimal? = BigDecimal("0.25"),
        spot: BigDecimal = BigDecimal("184"), // ITM (> 180)
        shortMid: BigDecimal? = BigDecimal("4.10"), // extrinsic = 4.10 − intrinsic(4) = 0.10 < 0.25
    ): BearCallStrategy {
        val marketDataPort =
            mockk<MarketDataPort> {
                coEvery { getUnderlyingPrice(symbol) } returns Money(spot)
                coEvery { getOptionMidLive(any()) } returns shortMid?.let { Money(it) }
            }
        val universePort =
            mockk<UniversePort> {
                every { getMarketSchedule(symbol) } returns
                    MarketSchedule(ZoneId.of("America/New_York"), LocalTime.of(9, 30), LocalTime.of(16, 0), session)
                coEvery { get(symbol) } returns InstrumentConfig(symbol = symbol, exDividendDate = exDiv, nextDividendAmount = dividend)
            }
        return BearCallStrategy(marketDataPort, universePort, config, clock)
    }

    @Test
    fun `force-closes when ex-div near, short call ITM, and extrinsic below dividend`() =
        runTest {
            assertEquals(SpreadStatus.CLOSED_DIVIDEND_RISK, strategy().strategyExitSignal(spread())?.status)
        }

    @Test
    fun `no exit for EU European-style names`() =
        runTest {
            assertNull(strategy(session = "EU").strategyExitSignal(spread()))
        }

    @Test
    fun `no exit when the short call is OTM`() =
        runTest {
            assertNull(strategy(spot = BigDecimal("175")).strategyExitSignal(spread()))
        }

    @Test
    fun `no exit when extrinsic exceeds the dividend`() =
        runTest {
            // spot 184, intrinsic 4, mid 5.00 → extrinsic 1.00 > dividend 0.25
            assertNull(strategy(shortMid = BigDecimal("5.00")).strategyExitSignal(spread()))
        }

    @Test
    fun `no exit when ex-dividend is outside the window`() =
        runTest {
            assertNull(strategy(exDiv = today.plusDays(10)).strategyExitSignal(spread()))
        }

    @Test
    fun `no exit when no ex-dividend date is known`() =
        runTest {
            assertNull(strategy(exDiv = null).strategyExitSignal(spread()))
        }

    @Test
    fun `force-closes as a fallback when the extrinsic cannot be priced`() =
        runTest {
            assertEquals(SpreadStatus.CLOSED_DIVIDEND_RISK, strategy(shortMid = null).strategyExitSignal(spread())?.status)
        }
}
