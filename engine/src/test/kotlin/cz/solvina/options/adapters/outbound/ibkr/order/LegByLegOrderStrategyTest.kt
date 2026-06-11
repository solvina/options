package cz.solvina.options.adapters.outbound.ibkr.order

import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderRegistry
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.Money
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import com.ib.client.EClientSocket
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegByLegOrderStrategyTest {
    private val client: EClientSocket = mockk(relaxed = true)
    private val registry: IbkrOrderRegistry = mockk()
    private val contractCache: IbkrContractCache = mockk()
    private val connectionConfig = IbkrConnectionConfig(account = "")

    private val strategy = LegByLegOrderStrategy(
        exchangeId = "EUREX",
        registry = registry,
        client = client,
        contractCache = contractCache,
        connectionConfig = connectionConfig,
    )

    @Test
    fun `issue 4 - monitors short fill before long submission`() = runTest {
        val shortOrderId = 100
        val soldContract = OptionContract(
            symbol = Symbol("AMD"),
            strike = BigDecimal("45.00"),
            expiry = LocalDate.of(2024, 6, 21),
            type = OptionType.PUT,
        )
        val boughtContract = OptionContract(
            symbol = Symbol("AMD"),
            strike = BigDecimal("40.00"),
            expiry = LocalDate.of(2024, 6, 21),
            type = OptionType.PUT,
        )

        // Setup: SHORT order will be tracked in registry
        val shortDeferred = CompletableDeferred<OrderStatus>()
        coEvery { registry.nextOrderId() }
            .returns(shortOrderId)
            .andThen(101) // LONG order ID
        coEvery { registry.pendingOrderStatus }
            .returns(ConcurrentHashMap(mapOf(shortOrderId to shortDeferred)))

        coEvery { contractCache.getOrFetchOptionConId(any()) }.returns(123456)

        // Mark SHORT as filled immediately (unhedged risk)
        shortDeferred.complete(OrderStatus.FILLED)

        val result = strategy.submitSpreadOrder(
            soldContract,
            boughtContract,
            Money(BigDecimal("1.50")),
            qty = 1,
        )

        // Should detect SHORT filled and cancel submission
        assertEquals(SubmissionStatus.LIQUIDITY_FAILED, result.status)
        assertTrue(result.message.contains("already filled before LONG submission"))
    }

    @Test
    fun `issue 4 - both legs submitted successfully`() = runTest {
        val shortOrderId = 200
        val longOrderId = 201

        val soldContract = OptionContract(
            symbol = Symbol("TSLA"),
            strike = BigDecimal("250.00"),
            expiry = LocalDate.of(2024, 6, 21),
            type = OptionType.PUT,
        )
        val boughtContract = OptionContract(
            symbol = Symbol("TSLA"),
            strike = BigDecimal("240.00"),
            expiry = LocalDate.of(2024, 6, 21),
            type = OptionType.PUT,
        )

        val shortDeferred = CompletableDeferred<OrderStatus>()
        val longDeferred = CompletableDeferred<OrderStatus>()

        var orderIdCounter = shortOrderId
        coEvery { registry.nextOrderId() }
            .answers { orderIdCounter++ }

        coEvery { registry.pendingOrderStatus }
            .returns(ConcurrentHashMap(mapOf(shortOrderId to shortDeferred, longOrderId to longDeferred)))

        coEvery { contractCache.getOrFetchOptionConId(any()) }.returns(123456)

        // Both orders pending (not filled yet)
        val result = strategy.submitSpreadOrder(
            soldContract,
            boughtContract,
            Money(BigDecimal("2.00")),
            qty = 1,
        )

        assertEquals(SubmissionStatus.SUCCESS, result.status)
        assertEquals(shortOrderId, result.primaryOrderId)
        assertEquals(longOrderId, result.secondaryOrderId)
    }

    @Test
    fun `issue 4 - long submission fails, short cancelled`() = runTest {
        val shortOrderId = 300
        val soldContract = OptionContract(
            symbol = Symbol("GOOG"),
            strike = BigDecimal("140.00"),
            expiry = LocalDate.of(2024, 6, 21),
            type = OptionType.PUT,
        )
        val boughtContract = OptionContract(
            symbol = Symbol("GOOG"),
            strike = BigDecimal("130.00"),
            expiry = LocalDate.of(2024, 6, 21),
            type = OptionType.PUT,
        )

        coEvery { registry.nextOrderId() }
            .returns(shortOrderId)
            .andThen(0) // LONG order submission fails

        coEvery { contractCache.getOrFetchOptionConId(any()) }.returns(123456)

        val result = strategy.submitSpreadOrder(
            soldContract,
            boughtContract,
            Money(BigDecimal("1.75")),
            qty = 1,
        )

        // SHORT submitted but LONG failed - should report failure
        assertEquals(SubmissionStatus.LIQUIDITY_FAILED, result.status)
        assertTrue(result.message.contains("LONG leg failed"))
    }

    @Test
    fun `await both legs with concurrent monitoring - both fill`() = runTest {
        val shortOrderId = 400
        val longOrderId = 401

        val shortDeferred = CompletableDeferred<OrderStatus>()
        val longDeferred = CompletableDeferred<OrderStatus>()

        coEvery { registry.pendingOrderStatus }
            .returns(ConcurrentHashMap(mapOf(shortOrderId to shortDeferred, longOrderId to longDeferred)))

        // Complete both fills concurrently
        shortDeferred.complete(OrderStatus.FILLED)
        longDeferred.complete(OrderStatus.FILLED)

        val result = strategy.awaitBothLegsWithConcurrentMonitoring(shortOrderId, longOrderId, timeoutMs = 1000)

        assertTrue(result)
    }

    @Test
    fun `await both legs - one fills one fails`() = runTest {
        val shortOrderId = 500
        val longOrderId = 501

        val shortDeferred = CompletableDeferred<OrderStatus>()
        val longDeferred = CompletableDeferred<OrderStatus>()

        coEvery { registry.pendingOrderStatus }
            .returns(ConcurrentHashMap(mapOf(shortOrderId to shortDeferred, longOrderId to longDeferred)))

        // SHORT fills, LONG cancelled
        shortDeferred.complete(OrderStatus.FILLED)
        longDeferred.complete(OrderStatus.CANCELLED)

        val result = strategy.awaitBothLegsWithConcurrentMonitoring(shortOrderId, longOrderId, timeoutMs = 1000)

        assertFalse(result)
    }
}
