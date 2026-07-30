package cz.solvina.options.spread

import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.PositionsPort
import cz.solvina.options.domain.features.order.LegAction
import cz.solvina.options.domain.features.spread.SpreadMarkService
import cz.solvina.options.domain.features.spread.model.BullPutSpread
import cz.solvina.options.domain.features.spread.model.SpreadLeg
import cz.solvina.options.domain.features.spread.model.SpreadStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpreadMarkServiceTest {
    private val now = Instant.parse("2026-07-30T18:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val expiry = LocalDate.of(2026, 8, 21)
    private val symbol = Symbol("AMD")

    private fun service(positions: List<AccountPosition>) =
        SpreadMarkService(
            positionsPort =
                object : PositionsPort {
                    override suspend fun getPositions() = positions
                },
            clock = clock,
            markMaxAgeMinutes = 20,
        )

    private fun failingService() =
        SpreadMarkService(
            positionsPort =
                object : PositionsPort {
                    override suspend fun getPositions(): List<AccountPosition> = throw IllegalStateException("feed down")
                },
            clock = clock,
            markMaxAgeMinutes = 20,
        )

    /** Bull put: sells the 420P for 1.50, buys the 415P for 0.50 → 1.00 credit per share. */
    private fun spread(
        qty: Int = 1,
        lastSpreadValue: BigDecimal? = BigDecimal("0.90"),
    ) = BullPutSpread(
        id = UUID.randomUUID(),
        symbol = symbol,
        soldLeg = SpreadLeg(opt("420"), LegAction.SELL, Money(BigDecimal("1.50")), orderId = 1),
        boughtLeg = SpreadLeg(opt("415"), LegAction.BUY, Money(BigDecimal("0.50")), orderId = 2),
        creditPerShare = BigDecimal("1.00"),
        maxRiskPerShare = BigDecimal("4.00"),
        quantity = qty,
        status = SpreadStatus.OPEN,
        ivRankAtEntry = 50.0,
        underlyingPriceAtEntry = BigDecimal("430"),
        openedAt = now.minusSeconds(86_400),
        lastSpreadValue = lastSpreadValue,
    )

    private fun opt(strike: String) = OptionContract(symbol, expiry, BigDecimal(strike), OptionType.PUT)

    private fun leg(
        strike: String,
        qty: Int,
        marketPrice: Double,
        unrealizedPnL: Double?,
        updatedAt: Instant? = now,
    ) = AccountPosition(
        account = "DU7875979",
        symbol = symbol.value,
        secType = "OPT",
        currency = "USD",
        expiry = expiry,
        // Deliberately scaled differently from the spread's strikes — 420.00 must match 420.
        strike = BigDecimal(strike).setScale(2),
        optionRight = "P",
        quantity = BigDecimal(qty),
        marketPrice = marketPrice,
        marketValue = marketPrice * 100 * qty,
        avgCost = BigDecimal("150"),
        conId = strike.toInt(),
        unrealizedPnL = unrealizedPnL,
        updatedAt = updatedAt,
    )

    @Test
    fun `sums IBKR per-leg unrealized PnL when the held legs are exactly this spread`() =
        runTest {
            val s = spread()
            val mark =
                service(
                    listOf(
                        leg("420", qty = -1, marketPrice = 0.80, unrealizedPnL = 70.0),
                        leg("415", qty = 1, marketPrice = 0.30, unrealizedPnL = -20.0),
                    ),
                ).mark(s)

            assertEquals(SpreadMarkService.Source.IBKR_PNL, mark.source)
            // Broker's own figure, not credit − mark (which would be 50.00).
            assertEquals(BigDecimal("50.00"), mark.unrealizedPnl)
            assertEquals(0, BigDecimal("0.50").compareTo(mark.spreadValuePerShare))
            assertTrue(mark.live)
        }

    @Test
    fun `broker PnL wins over a stale lastSpreadValue`() =
        runTest {
            // lastSpreadValue says 0.90 (≈ +10 P&L); the broker says the spread is worth 0.50.
            val s = spread(lastSpreadValue = BigDecimal("0.90"))
            val mark =
                service(
                    listOf(
                        leg("420", qty = -1, marketPrice = 0.80, unrealizedPnL = 70.0),
                        leg("415", qty = 1, marketPrice = 0.30, unrealizedPnL = -20.0),
                    ),
                ).mark(s)

            assertEquals(BigDecimal("50.00"), mark.unrealizedPnl)
        }

    @Test
    fun `derives PnL from broker marks when the held quantity is not exclusively this spread`() =
        runTest {
            // Two 1-lot spreads share these strikes, so IBKR's leg P&L covers both.
            val s = spread(qty = 1)
            val mark =
                service(
                    listOf(
                        leg("420", qty = -2, marketPrice = 0.80, unrealizedPnL = 140.0),
                        leg("415", qty = 2, marketPrice = 0.30, unrealizedPnL = -40.0),
                    ),
                ).mark(s)

            assertEquals(SpreadMarkService.Source.IBKR_MARK, mark.source)
            // (1.00 − 0.50) × 1 × 100 — our share, not the pooled 100.00.
            assertEquals(BigDecimal("50.00"), mark.unrealizedPnl)
            assertEquals(0, BigDecimal("0.50").compareTo(mark.spreadValuePerShare))
        }

    @Test
    fun `scales broker-derived PnL by contract quantity`() =
        runTest {
            val s = spread(qty = 3)
            val mark =
                service(
                    listOf(
                        leg("420", qty = -3, marketPrice = 0.80, unrealizedPnL = 210.0),
                        leg("415", qty = 3, marketPrice = 0.30, unrealizedPnL = -60.0),
                    ),
                ).mark(s)

            assertEquals(SpreadMarkService.Source.IBKR_PNL, mark.source)
            assertEquals(BigDecimal("150.00"), mark.unrealizedPnl)
        }

    @Test
    fun `falls back to the last monitor mark when a leg is missing from the feed`() =
        runTest {
            val s = spread(lastSpreadValue = BigDecimal("0.90"))
            val mark = service(listOf(leg("420", qty = -1, marketPrice = 0.80, unrealizedPnL = 70.0))).mark(s)

            assertEquals(SpreadMarkService.Source.LAST_MONITOR_MARK, mark.source)
            assertEquals(0, BigDecimal("0.90").compareTo(mark.spreadValuePerShare))
            assertEquals(BigDecimal("10.00"), mark.unrealizedPnl)
            assertFalse(mark.live)
        }

    @Test
    fun `treats a portfolio row the broker stopped pushing as cold`() =
        runTest {
            val stale = now.minusSeconds(21 * 60)
            val s = spread(lastSpreadValue = BigDecimal("0.90"))
            val mark =
                service(
                    listOf(
                        leg("420", qty = -1, marketPrice = 0.80, unrealizedPnL = 70.0, updatedAt = stale),
                        leg("415", qty = 1, marketPrice = 0.30, unrealizedPnL = -20.0, updatedAt = stale),
                    ),
                ).mark(s)

            assertEquals(SpreadMarkService.Source.LAST_MONITOR_MARK, mark.source)
        }

    @Test
    fun `keeps a genuine zero mark on a worthless leg`() =
        runTest {
            // Max profit: the short leg really is worthless. Unlike the quote path, a 0.00 from the
            // portfolio feed is a real IBKR mark and must not be discarded.
            val s = spread()
            val mark =
                service(
                    listOf(
                        leg("420", qty = -1, marketPrice = 0.0, unrealizedPnL = 150.0),
                        leg("415", qty = 1, marketPrice = 0.0, unrealizedPnL = -50.0),
                    ),
                ).mark(s)

            assertEquals(SpreadMarkService.Source.IBKR_PNL, mark.source)
            assertEquals(0, BigDecimal.ZERO.compareTo(mark.spreadValuePerShare))
            assertEquals(BigDecimal("100.00"), mark.unrealizedPnl)
        }

    @Test
    fun `falls back when the feed reports no price for a leg`() =
        runTest {
            val s = spread(lastSpreadValue = BigDecimal("0.90"))
            val mark =
                service(
                    listOf(
                        leg("420", qty = -1, marketPrice = -1.0, unrealizedPnL = 70.0),
                        leg("415", qty = 1, marketPrice = 0.30, unrealizedPnL = -20.0),
                    ),
                ).mark(s)

            assertEquals(SpreadMarkService.Source.LAST_MONITOR_MARK, mark.source)
        }

    @Test
    fun `reports nothing rather than zero when neither source has a value`() =
        runTest {
            val mark = service(emptyList()).mark(spread(lastSpreadValue = null))

            assertEquals(SpreadMarkService.Source.LAST_MONITOR_MARK, mark.source)
            assertNull(mark.spreadValuePerShare)
            assertNull(mark.unrealizedPnl)
        }

    @Test
    fun `a broken portfolio feed degrades to the last monitor mark instead of throwing`() =
        runTest {
            val mark = failingService().mark(spread(lastSpreadValue = BigDecimal("0.90")))

            assertEquals(SpreadMarkService.Source.LAST_MONITOR_MARK, mark.source)
            assertEquals(BigDecimal("10.00"), mark.unrealizedPnl)
        }

    @Test
    fun `marks only live spreads and keys them by id`() =
        runTest {
            val open = spread()
            val closed = spread().copy(status = SpreadStatus.CLOSED_PROFIT, closePricePerShare = BigDecimal("0.10"))
            val marks =
                service(
                    listOf(
                        leg("420", qty = -1, marketPrice = 0.80, unrealizedPnL = 70.0),
                        leg("415", qty = 1, marketPrice = 0.30, unrealizedPnL = -20.0),
                    ),
                ).marks(listOf(open, closed))

            assertEquals(setOf(open.id), marks.keys)
            assertEquals(SpreadMarkService.Source.IBKR_PNL, marks[open.id]?.source)
        }
}
