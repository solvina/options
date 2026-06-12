package cz.solvina.options.adapters.outbound.ibkr.cache

import com.ib.client.Contract
import com.ib.client.ContractDetails
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.IbkrInstrumentsConfig
import cz.solvina.options.adapters.outbound.ibkr.InstrumentDef
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrContractRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingContractRequest
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals

class IbkrContractCacheInFlightTest {
    private val registry: IbkrContractRegistry = mockk()
    private val client: EClientSocket = mockk(relaxed = true)
    private val instrumentsConfig =
        IbkrInstrumentsConfig(
            instruments = mapOf("ASML" to InstrumentDef(currency = "EUR", exchange = "AEB", optionExchange = "EUREX")),
            exchanges = emptyMap(),
        )
    private val contractFactory = IbkrContractFactory(instrumentsConfig)
    private val optionParamsCache: IbkrOptionParamsCache = mockk()

    private val pendingContractDetails = ConcurrentHashMap<Int, PendingContractRequest>()
    private val timedOutReqIds = ConcurrentHashMap.newKeySet<Int>()

    private val cache =
        IbkrContractCache(
            registry = registry,
            client = client,
            contractFactory = contractFactory,
        )

    private val symbol = Symbol("ASML")
    private val expiry = LocalDate.of(2026, 7, 17)
    private val key = OptionContractKey(symbol, expiry, BigDecimal("1360.0"), OptionType.PUT)

    @Test
    fun `concurrent fetch same key - only one reqContractDetails issued`() =
        runTest {
            var reqIdCounter = 10
            every { registry.nextReqId() } answers { reqIdCounter++ }
            every { registry.pendingContractDetails } returns pendingContractDetails
            every { registry.timedOutReqIds } returns timedOutReqIds
            every { optionParamsCache.getCached(symbol) } returns null

            // Simulate IBKR responding to the first reqContractDetails
            every { client.reqContractDetails(any(), any()) } answers {
                val reqId = firstArg<Int>()
                val pending = pendingContractDetails[reqId] ?: return@answers
                val contractDetails = mockk<ContractDetails>()
                val contract = mockk<Contract>()
                every { contract.strike() } returns 1360.0
                every { contract.exchange() } returns "EUREX"
                every { contract.tradingClass() } returns "OES"
                every { contract.conid() } returns 999_001
                every { contractDetails.contract() } returns contract
                pending.details.add(contractDetails)
                pending.deferred.complete(pending.details.toList())
            }

            // Launch two coroutines requesting the same key simultaneously
            val result1 = async { cache.getOrFetchOptionConId(key) }
            val result2 = async { cache.getOrFetchOptionConId(key) }

            val conId1 = result1.await()
            val conId2 = result2.await()

            assertEquals(999_001, conId1)
            assertEquals(999_001, conId2)
            // Only one network request should have been made
            verify(exactly = 1) { client.reqContractDetails(any(), any()) }
        }

    @Test
    fun `second call after first completes uses cached result - no network call`() =
        runTest {
            var reqIdCounter = 20
            every { registry.nextReqId() } answers { reqIdCounter++ }
            every { registry.pendingContractDetails } returns pendingContractDetails
            every { registry.timedOutReqIds } returns timedOutReqIds
            every { optionParamsCache.getCached(symbol) } returns null

            every { client.reqContractDetails(any(), any()) } answers {
                val reqId = firstArg<Int>()
                val pending = pendingContractDetails[reqId] ?: return@answers
                val contractDetails = mockk<ContractDetails>()
                val contract = mockk<Contract>()
                every { contract.strike() } returns 1360.0
                every { contract.exchange() } returns "EUREX"
                every { contract.tradingClass() } returns "OES"
                every { contract.conid() } returns 888_001
                every { contractDetails.contract() } returns contract
                pending.details.add(contractDetails)
                pending.deferred.complete(pending.details.toList())
            }

            // First fetch populates cache
            val conId1 = cache.getOrFetchOptionConId(key)
            // Second fetch should hit cache directly
            val conId2 = cache.getOrFetchOptionConId(key)

            assertEquals(888_001, conId1)
            assertEquals(888_001, conId2)
            verify(exactly = 1) { client.reqContractDetails(any(), any()) }
        }
}
