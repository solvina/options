package cz.solvina.options.adapters.outbound.ibkr.order

import com.ib.client.EClientSocket
import com.ib.client.Order
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.LegQuotes
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the LONG-first leg-by-leg contract: the protective long is submitted and confirmed before
 * the short, so the worst case is a paid-for long (bounded debit), never a naked short.
 *
 * Fills are driven through the real [pending] deferred map: [LegByLegOrderStrategy] registers a fresh
 * deferred under each new orderId before calling [EClientSocket.placeOrder], so the placeOrder mock
 * completes that deferred according to [fillByAction] — simulating an immediate broker fill (or, when
 * the action maps to null, a leg that never fills and times out).
 */
class LegByLegOrderStrategyTest {
    private val client: EClientSocket = mockk(relaxed = true)
    private val registry: IbkrOrderRegistry = mockk()
    private val contractCache: IbkrContractCache = mockk()
    private val connectionConfig = IbkrConnectionConfig(account = "")

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<OrderStatus>>()
    private val nextId = AtomicInteger(1000)

    /** action ("BUY"/"SELL") -> status the placeOrder hook completes that leg with; null = never fills. */
    private val fillByAction = mutableMapOf<String, OrderStatus?>("BUY" to OrderStatus.FILLED, "SELL" to OrderStatus.FILLED)

    /** Every placed order in submission order, as (action, order) for assertions. */
    private val placedOrders = mutableListOf<Pair<String, Order>>()

    @BeforeTest
    fun setup() {
        every { registry.nextOrderId() } answers { nextId.getAndIncrement() }
        every { registry.pendingOrderStatus } returns pending
        coEvery { contractCache.getOrFetchOptionConId(any()) } returns 123456
        every { client.placeOrder(any(), any(), any()) } answers {
            val id = firstArg<Int>()
            val order = thirdArg<Order>()
            val action = order.action().toString()
            placedOrders.add(action to order)
            fillByAction[action]?.let { status -> pending[id]?.complete(status) }
        }
    }

    private fun buildStrategy(unwindStrandedLongLeg: Boolean = false) =
        LegByLegOrderStrategy(
            exchangeId = "EUREX",
            registry = registry,
            client = client,
            contractCache = contractCache,
            connectionConfig = connectionConfig,
            unwindStrandedLongLeg = unwindStrandedLongLeg,
            legFillTimeoutMs = 1_000, // runTest advances virtual time, so timeouts resolve instantly
        )

    private fun sold(
        strike: String = "250.00",
        expiry: LocalDate = LocalDate.of(2024, 6, 21),
    ) = OptionContract(symbol = Symbol("TSLA"), strike = BigDecimal(strike), expiry = expiry, type = OptionType.PUT)

    private fun bought(
        strike: String = "240.00",
        expiry: LocalDate = LocalDate.of(2024, 6, 21),
    ) = OptionContract(symbol = Symbol("TSLA"), strike = BigDecimal(strike), expiry = expiry, type = OptionType.PUT)

    private fun legQuotes(
        soldBid: String,
        soldAsk: String,
        boughtBid: String,
        boughtAsk: String,
    ) = LegQuotes(BigDecimal(soldBid), BigDecimal(soldAsk), BigDecimal(boughtBid), BigDecimal(boughtAsk))

    @Test
    fun `rejects when short strike is not above long strike`() =
        runTest {
            val result =
                buildStrategy().submitSpreadOrder(
                    soldContract = sold("40.00"),
                    boughtContract = bought("45.00"),
                    netCredit = Money(BigDecimal("1.50")),
                    qty = 1,
                )

            assertEquals(SubmissionStatus.REJECTED, result.status)
            assertTrue(result.message.contains("Short strike must be > long strike"))
            assertTrue(placedOrders.isEmpty(), "no legs should be placed on a rejected spread")
        }

    @Test
    fun `rejects when short leg has no positive bid`() =
        runTest {
            val result =
                buildStrategy().submitSpreadOrder(
                    soldContract = sold(),
                    boughtContract = bought(),
                    netCredit = Money(BigDecimal("2.00")),
                    qty = 1,
                    legQuotes = legQuotes(soldBid = "0.00", soldAsk = "0.05", boughtBid = "0.40", boughtAsk = "0.50"),
                )

            assertEquals(SubmissionStatus.REJECTED, result.status)
            assertTrue(result.message.contains("no positive bid"))
            assertTrue(placedOrders.isEmpty())
        }

    @Test
    fun `both legs fill - LONG submitted first then SHORT`() =
        runTest {
            val result =
                buildStrategy().submitSpreadOrder(
                    soldContract = sold(),
                    boughtContract = bought(),
                    netCredit = Money(BigDecimal("2.00")),
                    qty = 1,
                    legQuotes = legQuotes(soldBid = "1.30", soldAsk = "1.40", boughtBid = "0.45", boughtAsk = "0.55"),
                )

            assertEquals(SubmissionStatus.SUCCESS, result.status)
            // LONG is allocated first (id 1000), SHORT second (id 1001).
            assertEquals(1001, result.primaryOrderId, "primary should be the SHORT order id")
            assertEquals(1000, result.secondaryOrderId, "secondary should be the LONG order id")
            assertEquals(listOf("BUY", "SELL"), placedOrders.map { it.first }, "protective LONG must be placed before SHORT")
        }

    @Test
    fun `protective LONG never fills - aborts before submitting SHORT`() =
        runTest {
            fillByAction["BUY"] = null // long never fills → times out

            val result =
                buildStrategy().submitSpreadOrder(
                    soldContract = sold(),
                    boughtContract = bought(),
                    netCredit = Money(BigDecimal("2.00")),
                    qty = 1,
                    legQuotes = legQuotes(soldBid = "1.30", soldAsk = "1.40", boughtBid = "0.45", boughtAsk = "0.55"),
                )

            assertEquals(SubmissionStatus.LIQUIDITY_FAILED, result.status)
            assertEquals(1000, result.primaryOrderId)
            assertEquals(listOf("BUY"), placedOrders.map { it.first }, "no SHORT may be placed when the LONG did not fill")
        }

    @Test
    fun `LONG submission unavailable - reports failure with no exposure`() =
        runTest {
            every { registry.nextOrderId() } returns 0 // submission unavailable

            val result =
                buildStrategy().submitSpreadOrder(
                    soldContract = sold(),
                    boughtContract = bought(),
                    netCredit = Money(BigDecimal("2.00")),
                    qty = 1,
                )

            assertEquals(SubmissionStatus.LIQUIDITY_FAILED, result.status)
            assertTrue(placedOrders.isEmpty(), "no order should reach the broker when nextOrderId is unavailable")
        }

    @Test
    fun `SHORT does not fill with auto-unwind OFF - returns STRANDED_LONG`() =
        runTest {
            fillByAction["SELL"] = null // short never fills

            val result =
                buildStrategy(unwindStrandedLongLeg = false).submitSpreadOrder(
                    soldContract = sold(),
                    boughtContract = bought(),
                    netCredit = Money(BigDecimal("2.00")),
                    qty = 1,
                    legQuotes = legQuotes(soldBid = "1.30", soldAsk = "1.40", boughtBid = "0.45", boughtAsk = "0.55"),
                )

            assertEquals(SubmissionStatus.STRANDED_LONG, result.status)
            assertEquals(1000, result.primaryOrderId, "primary should be the filled LONG order id")
            assertNull(result.secondaryOrderId, "no unwind order when auto-unwind is OFF")
            assertEquals(listOf("BUY", "SELL"), placedOrders.map { it.first }, "LONG filled, SHORT attempted")
        }

    @Test
    fun `SHORT does not fill with auto-unwind ON - sells the LONG back`() =
        runTest {
            fillByAction["SELL"] = null // both the short and the unwind sell stay pending

            val result =
                buildStrategy(unwindStrandedLongLeg = true).submitSpreadOrder(
                    soldContract = sold(),
                    boughtContract = bought(),
                    netCredit = Money(BigDecimal("2.00")),
                    qty = 1,
                    legQuotes = legQuotes(soldBid = "1.30", soldAsk = "1.40", boughtBid = "0.45", boughtAsk = "0.55"),
                )

            assertEquals(SubmissionStatus.LIQUIDITY_FAILED, result.status)
            assertEquals(1000, result.primaryOrderId, "primary should be the filled LONG order id")
            assertNotNull(result.secondaryOrderId, "unwind sell order id should be reported")
            assertTrue(result.message.contains("auto-unwound"))
            // BUY (long), SELL (failed short), SELL (unwind of the long)
            assertEquals(listOf("BUY", "SELL", "SELL"), placedOrders.map { it.first })
        }

    @Test
    fun `prices each leg from its own fresh quote - long at ask, short at bid`() =
        runTest {
            buildStrategy().submitSpreadOrder(
                soldContract = sold(),
                boughtContract = bought(),
                netCredit = Money(BigDecimal("2.00")),
                qty = 1,
                legQuotes = legQuotes(soldBid = "1.234", soldAsk = "1.40", boughtBid = "0.45", boughtAsk = "0.567"),
            )

            val buy = placedOrders.first { it.first == "BUY" }.second
            val sell = placedOrders.first { it.first == "SELL" }.second
            // long pays the ask, ceil-to-tick: 0.567 -> 0.57
            assertEquals(0.57, buy.lmtPrice(), 1e-9)
            // short hits the bid, floor-to-tick: 1.234 -> 1.23
            assertEquals(1.23, sell.lmtPrice(), 1e-9)
        }

    @Test
    fun `validateOrder rejects mismatched expiry`() =
        runTest {
            val result =
                buildStrategy().submitSpreadOrder(
                    soldContract = sold(),
                    boughtContract = bought(expiry = LocalDate.of(2024, 7, 19)),
                    netCredit = Money(BigDecimal("2.00")),
                    qty = 1,
                )

            assertEquals(SubmissionStatus.REJECTED, result.status)
            assertTrue(result.message.contains("same expiry"))
        }
}
