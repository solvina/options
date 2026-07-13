package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Contract
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.IbkrInstrumentsConfig
import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionConfig
import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionController
import cz.solvina.options.adapters.outbound.ibkr.InstrumentDef
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrOptionParamsCache
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionContractKey
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionParams
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrMarketDataRegistry
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

class IbkrMarketTickAdapterContractResolutionTest {
    private val registry: IbkrMarketDataRegistry = mockk(relaxed = true)
    private val client: EClientSocket = mockk(relaxed = true)
    private val contractCache: IbkrContractCache =
        mockk {
            // No recorded listing venue — contractForMktData falls back to the instrument def's
            // optionExchange, which is what these tests assert on.
            every { getCachedOptionConIdExchange(any()) } returns null
        }
    private val optionParamsCache: IbkrOptionParamsCache = mockk()
    private val instrumentsConfig =
        IbkrInstrumentsConfig(
            instruments = mapOf("ASML" to InstrumentDef(currency = "EUR", exchange = "AEB", optionExchange = "EUREX")),
            exchanges = emptyMap(),
        )
    private val contractFactory = IbkrContractFactory(instrumentsConfig)

    private val adapter =
        IbkrMarketTickAdapter(
            registry = registry,
            client = client,
            contractFactory = contractFactory,
            contractCache = contractCache,
            optionParamsCache = optionParamsCache,
            admission = IbkrAdmissionController(IbkrAdmissionConfig(), java.time.Clock.systemUTC()),
            connectionConfig = IbkrConnectionConfig(useLiveMarketData = true),
            // SupervisorJob (matching production) so a failed fetch surfaces only via await();
            // Unconfined runs the detached fetch inline so resolution completes deterministically
            // within runTest's virtual time (no real-thread dispatch).
            conIdScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

    private val symbol = Symbol("ASML")
    private val expiry = LocalDate.of(2026, 7, 17)
    private val soldContract = OptionContract(symbol, expiry, BigDecimal("1360.0"), OptionType.PUT)
    private val boughtContract = OptionContract(symbol, expiry, BigDecimal("1350.0"), OptionType.PUT)
    private val soldKey = OptionContractKey(symbol, expiry, BigDecimal("1360.0"), OptionType.PUT)
    private val boughtKey = OptionContractKey(symbol, expiry, BigDecimal("1350.0"), OptionType.PUT)

    @Test
    fun `cache hit - uses conIdContract without fetching`() =
        runTest {
            val soldConId = 111
            val boughtConId = 222

            // Cache already populated (e.g. from a prior order submission)
            // getCachedOptionConId returns the cached value directly without fetching
            every { contractCache.getCachedOptionConId(soldKey) } returns soldConId
            every { contractCache.getCachedOptionConId(boughtKey) } returns boughtConId

            val contracts = captureContracts()

            // conId identifies the instrument; the exchange is still REQUIRED on the market-data
            // request (IBKR error 321 "Please enter exchange" without it) and must be the venue —
            // EUREX for this ASML instrument (SMART for US).
            assertEquals(soldConId, contracts.first.conid())
            assertEquals(boughtConId, contracts.second.conid())
            assertEquals("EUREX", contracts.first.exchange())
            assertEquals("EUREX", contracts.second.exchange())
        }

    @Test
    fun `cache miss fetch succeeds - lazily fetches conId and uses conIdContract`() =
        runTest {
            val soldConId = 333
            val boughtConId = 444

            // Cache miss: getCachedOptionConId returns null, so adapter calls getOrFetchOptionConId
            every { contractCache.getCachedOptionConId(soldKey) } returns null
            every { contractCache.getCachedOptionConId(boughtKey) } returns null
            coEvery { contractCache.getOrFetchOptionConId(soldKey) } returns soldConId
            coEvery { contractCache.getOrFetchOptionConId(boughtKey) } returns boughtConId

            val contracts = captureContracts()

            assertEquals(soldConId, contracts.first.conid())
            assertEquals(boughtConId, contracts.second.conid())
            assertEquals("EUREX", contracts.first.exchange())
            assertEquals("EUREX", contracts.second.exchange())
        }

    @Test
    fun `cache miss fetch fails - falls back to enriched optionContract with exchange`() =
        runTest {
            val params =
                OptionParams(
                    expirations = emptySet(),
                    strikes = emptySet(),
                    strikesByExpiry = emptyMap(),
                    fetchedAt = Instant.now(),
                    exchange = "EUREX",
                    tradingClass = "OES",
                    multiplier = "100",
                )

            // Cache miss, fetch fails (timeout, ambiguous, etc.)
            every { contractCache.getCachedOptionConId(any()) } returns null
            coEvery { contractCache.getOrFetchOptionConId(any()) } throws IllegalStateException("lookup timeout")
            every { optionParamsCache.getCached(symbol) } returns params

            val contracts = captureContracts()

            // Should fall back to minimal contract spec (symbol+secType only, no exchange/tradingClass
            // as they can cause error 200 "not found" for US options)
            assertEquals(0, contracts.first.conid())
            assertEquals("ASML", contracts.first.symbol())
            assertEquals("OPT", contracts.first.secType().toString())
        }

    @Test
    fun `cache miss fetch fails with no cached params - falls back to instrument def exchange`() =
        runTest {
            every { contractCache.getCachedOptionConId(any()) } returns null
            coEvery { contractCache.getOrFetchOptionConId(any()) } throws IllegalStateException("timeout")
            every { optionParamsCache.getCached(symbol) } returns null

            val contracts = captureContracts()

            assertEquals(0, contracts.first.conid())
            assertEquals("ASML", contracts.first.symbol())
            // instrumentDef.optionExchange = "EUREX"
            assertEquals("EUREX", contracts.first.exchange())
        }

    /** Captures the two contracts passed to market data requests. */
    private suspend fun captureContracts(): Pair<Contract, Contract> {
        val captured = mutableListOf<Contract>()

        // Intercept reqMktData and reqTickByTickData calls to capture the contracts
        every { client.reqMktData(any(), capture(captured), any(), any(), any(), any()) } returns Unit
        every { client.reqTickByTickData(any(), capture(captured), any(), any(), any()) } returns Unit
        every { registry.nextReqId() } returnsMany (100..200).toList()

        // Launch the flow in a separate coroutine and let it build up the request infrastructure,
        // then cancel it immediately. We only care about what contracts are passed to the client.
        coroutineScope {
            val job =
                launch {
                    adapter.streamSpreadCredit(soldContract, boughtContract).collect { }
                }
            // Give it a moment to set up the requests
            delay(10)
            job.cancel()
        }

        // reqTickByTickData is called first for each leg → indices 0 and 1 are the leg contracts
        return Pair(captured[0], captured[1])
    }
}
