package cz.solvina.options.adapters.outbound.ibkr.cache

import com.ib.client.Contract
import com.ib.client.ContractDetails
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.IbkrInstrumentsConfig
import cz.solvina.options.adapters.outbound.ibkr.InstrumentDef
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrContractRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrIdCounter
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IbkrContractCacheInFlightTest {
    private val idCounter: IbkrIdCounter = mockk(relaxed = true)
    private val registry: IbkrContractRegistry = IbkrContractRegistry(idCounter)
    private val client: EClientSocket = mockk(relaxed = true)
    private val instrumentsConfig =
        IbkrInstrumentsConfig(
            instruments = mapOf("ASML" to InstrumentDef(currency = "EUR", exchange = "AEB", optionExchange = "EUREX")),
            exchanges = emptyMap(),
        )
    private val contractFactory = IbkrContractFactory(instrumentsConfig)
    private val optionParamsCache: IbkrOptionParamsCache = mockk()

    private val cache =
        IbkrContractCache(
            registry = registry,
            client = client,
            contractFactory = contractFactory,
        )

    @BeforeEach
    fun setUp() {
        ReflectionTestUtils.setField(cache, "optionParamsCache", optionParamsCache)
    }

    private val symbol = Symbol("ASML")
    private val expiry = LocalDate.of(2026, 7, 17)
    private val key = OptionContractKey(symbol, expiry, BigDecimal("1360.0"), OptionType.PUT)

    @Test
    fun `concurrent fetch same key - only one reqContractDetails issued`() =
        runTest {
            var reqIdCounter = 10
            every { optionParamsCache.getCached(symbol) } returns null

            // Simulate IBKR responding to the first reqContractDetails
            every { client.reqContractDetails(any(), any()) } answers {
                val reqId = firstArg<Int>()
                val pending = registry.pendingContractDetails[reqId] ?: return@answers
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
            every { optionParamsCache.getCached(symbol) } returns null

            every { client.reqContractDetails(any(), any()) } answers {
                val reqId = firstArg<Int>()
                val pending = registry.pendingContractDetails[reqId] ?: return@answers
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

    @Test
    fun `cancelled in-flight lookup is cleaned up so a later call re-fetches (E3)`() =
        runTest {
            every { optionParamsCache.getCached(symbol) } returns null

            // First attempt: IBKR never responds (relaxed mock does nothing), so the lookup hangs.
            // An aggressive caller-side timeout cancels it mid-flight (the E3 scenario).
            val firstAttempt =
                runCatching {
                    withTimeout(50) { cache.getOrFetchOptionConId(key) }
                }
            assertTrue(firstAttempt.isFailure, "first lookup should have been cancelled")

            // Now IBKR responds. The cancelled lookup must NOT have left an orphaned in-flight
            // deferred — otherwise this call would await it forever. It must re-fetch and succeed.
            every { client.reqContractDetails(any(), any()) } answers {
                val reqId = firstArg<Int>()
                val pending = registry.pendingContractDetails[reqId] ?: return@answers
                val contractDetails = mockk<ContractDetails>()
                val contract = mockk<Contract>()
                every { contract.strike() } returns 1360.0
                every { contract.exchange() } returns "EUREX"
                every { contract.tradingClass() } returns "OES"
                every { contract.conid() } returns 777_001
                every { contractDetails.contract() } returns contract
                pending.details.add(contractDetails)
                pending.deferred.complete(pending.details.toList())
            }

            val conId = cache.getOrFetchOptionConId(key)
            assertEquals(777_001, conId)
        }
}
