package cz.solvina.options.account

import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.BrokerStockPositionService
import cz.solvina.options.domain.features.account.PositionsPort
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrokerStockPositionServiceTest {
    private val now = Instant.parse("2026-07-30T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun service(positions: List<AccountPosition>) =
        BrokerStockPositionService(
            positionsPort =
                object : PositionsPort {
                    override suspend fun getPositions() = positions
                },
            clock = clock,
            markMaxAgeMinutes = 20,
        )

    private fun failingService() =
        BrokerStockPositionService(
            positionsPort =
                object : PositionsPort {
                    override suspend fun getPositions(): List<AccountPosition> = throw IllegalStateException("feed down")
                },
            clock = clock,
            markMaxAgeMinutes = 20,
        )

    private fun stk(
        symbol: String = "AMT",
        shares: Int = 45,
        marketPrice: Double = 179.25,
        avgCost: String = "178.8822",
        unrealizedPnL: Double? = 16.55,
        realizedPnL: Double? = 0.0,
        updatedAt: Instant? = now,
        conId: Int = 1,
    ) = AccountPosition(
        account = "DU7875979",
        symbol = symbol,
        secType = "STK",
        currency = "USD",
        expiry = null,
        strike = null,
        optionRight = null,
        quantity = BigDecimal(shares),
        marketPrice = marketPrice,
        marketValue = marketPrice * shares,
        avgCost = BigDecimal(avgCost),
        conId = conId,
        unrealizedPnL = unrealizedPnL,
        realizedPnL = realizedPnL,
        updatedAt = updatedAt,
    )

    @Test
    fun `reports the broker row verbatim`() =
        runTest {
            val view = service(listOf(stk())).bySymbol().getValue("AMT")

            // The live AMT case: our own estimate said −47.25 while IBKR said +16.55. The broker's
            // figure must arrive untouched, together with the cost basis that explains it.
            assertEquals(BigDecimal("16.55"), view.unrealizedPnl)
            assertEquals(BigDecimal("178.8822"), view.avgCost)
            assertEquals(0, BigDecimal("179.25").compareTo(view.marketPrice))
            assertEquals(0, BigDecimal("8066.25").compareTo(view.marketValue))
            assertEquals(0, BigDecimal(45).compareTo(view.shares))
            assertEquals(BigDecimal("0.00"), view.realizedPnl)
            assertEquals(now, view.updatedAt)
            assertFalse(view.stale)
        }

    @Test
    fun `flags a push older than the configured age as stale but still reports it`() =
        runTest {
            val view = service(listOf(stk(updatedAt = now.minusSeconds(21 * 60)))).bySymbol().getValue("AMT")

            assertTrue(view.stale)
            // Hiding the value would be worse than labelling it — the operator still wants the number.
            assertEquals(BigDecimal("16.55"), view.unrealizedPnl)
        }

    @Test
    fun `ignores option legs and flat rows`() =
        runTest {
            val option =
                AccountPosition(
                    account = "DU7875979",
                    symbol = "AMD",
                    secType = "OPT",
                    currency = "USD",
                    expiry = LocalDate.of(2026, 8, 21),
                    strike = BigDecimal("420"),
                    optionRight = "P",
                    quantity = BigDecimal(-1),
                    marketPrice = 0.8,
                    marketValue = -80.0,
                    avgCost = BigDecimal("150"),
                    conId = 2,
                    updatedAt = now,
                )
            val closedOut = stk(symbol = "SBUX", shares = 0, conId = 3)

            val bySymbol = service(listOf(stk(), option, closedOut)).bySymbol()

            assertEquals(setOf("AMT"), bySymbol.keys)
        }

    @Test
    fun `refuses to blend two stock rows for one symbol`() =
        runTest {
            // Two listings of the same symbol cannot be collapsed without inventing an avgCost the
            // broker never sent, so report nothing for it rather than a fabricated number.
            val bySymbol =
                service(
                    listOf(
                        stk(conId = 1),
                        stk(marketPrice = 150.0, avgCost = "149.00", conId = 2),
                    ),
                ).bySymbol()

            assertNull(bySymbol["AMT"])
        }

    @Test
    fun `a broken feed yields no broker view instead of throwing`() =
        runTest {
            assertEquals(emptyMap(), failingService().bySymbol())
        }
}
