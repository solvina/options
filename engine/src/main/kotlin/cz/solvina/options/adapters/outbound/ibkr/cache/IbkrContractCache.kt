package cz.solvina.options.adapters.outbound.ibkr.cache

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrContractRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingContractRequest
import cz.solvina.options.domain.models.OptionType
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

@Component
class IbkrContractCache(
    private val registry: IbkrContractRegistry,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
) {
    @Lazy @Autowired
    private lateinit var optionParamsCache: IbkrOptionParamsCache
    private val underlyingConIds = ConcurrentHashMap<Symbol, Int>()
    private val optionConIds = ConcurrentHashMap<OptionContractKey, Int>()
    private val inFlightOptionConIds = ConcurrentHashMap<OptionContractKey, Deferred<Int>>()

    // Negative cache: a key maps to WHEN it was last marked missing. Time-boxed so a single
    // transient/incomplete IBKR response doesn't block the contract for the rest of the day (E5).
    private val missingContracts = ConcurrentHashMap<OptionContractKey, Instant>()
    private val missingContractTtl: Duration = Duration.ofMinutes(10)

    // Strikes round-trip through Double via the IBKR API; compare with a tolerance rather than `==`
    // so e.g. 412.5 never mismatches itself (E7).
    private val strikeEpsilon = 1e-4

    private data class VerifiedKey(
        val symbol: Symbol,
        val expiry: LocalDate,
        val optionType: OptionType,
    )

    private val verifiedStrikes = ConcurrentHashMap<VerifiedKey, TreeSet<BigDecimal>>()

    suspend fun getOrFetchUnderlyingConId(symbol: Symbol): Int {
        underlyingConIds[symbol]?.let { return it }

        logger.debug { "[$symbol] Fetching underlying conId" }
        val reqId = registry.nextReqId()
        val deferred = CompletableDeferred<List<com.ib.client.ContractDetails>>()
        registry.pendingContractDetails[reqId] = PendingContractRequest(deferred, CopyOnWriteArrayList())

        client.reqContractDetails(reqId, contractFactory.stockContract(symbol))

        val details: List<com.ib.client.ContractDetails> =
            try {
                withTimeout(5000L) {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                registry.pendingContractDetails.remove(reqId)
                registry.timedOutReqIds.add(reqId)
                logger.error { "[$symbol] Contract lookup timeout (5s) — IBKR not responding" }
                error("Contract lookup timeout for $symbol after 5s")
            }

        val conId =
            details.firstOrNull()?.contract()?.conid()
                ?: error("No stock contract found for $symbol")

        underlyingConIds[symbol] = conId
        logger.debug { "[$symbol] Underlying conId = $conId" }
        return conId
    }

    suspend fun getOrFetchOptionConId(key: OptionContractKey): Int {
        optionConIds[key]?.let { return it }
        if (isMissing(key)) error("No option contract found for $key (cached miss)")

        evictExpired()

        logger.debug { "[$key] Fetching option conId" }
        val resultDeferred = CompletableDeferred<Int>()
        // Atomically claim the in-flight slot. If another coroutine is already fetching this key,
        // piggyback on its result instead of issuing a duplicate IBKR request (E6).
        inFlightOptionConIds.putIfAbsent(key, resultDeferred)?.let { return it.await() }
        // Defence against external cancellation (e.g. an aggressive caller-side withTimeout): if this
        // lookup is cancelled before it completes the deferred on its own, clear the in-flight entry and
        // fail the deferred so piggybacking callers don't await a value that will never arrive.
        currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) {
                inFlightOptionConIds.remove(key, resultDeferred)
                if (!resultDeferred.isCompleted) {
                    resultDeferred.completeExceptionally(
                        IllegalStateException("Option conId fetch for $key was cancelled before completion"),
                    )
                }
            }
        }

        val reqId = registry.nextReqId()
        val deferred = CompletableDeferred<List<com.ib.client.ContractDetails>>()
        registry.pendingContractDetails[reqId] = PendingContractRequest(deferred, CopyOnWriteArrayList())

        val cachedParams = optionParamsCache.getCached(key.symbol)
        val cachedExchange = cachedParams?.exchange
        val cachedTradingClass = cachedParams?.tradingClass
        val def = contractFactory.defFor(key.symbol)
        // Minimal search: symbol+secType+currency+expiry+right only — no exchange, no tradingClass, no strike.
        // Adding tradingClass or exchange causes error 200 "not found" for both EU (EUREX) and US options.
        // Filter by strike in the response instead.
        val searchContract =
            com.ib.client.Contract().apply {
                symbol(key.symbol.value)
                secType("OPT")
                currency(def.currency)
                lastTradeDateOrContractMonth(
                    key.expiry.format(
                        java.time.format.DateTimeFormatter
                            .ofPattern("yyyyMMdd"),
                    ),
                )
                right(key.optionType.ibkrCode)
            }
        logger.debug {
            "[$key] reqContractDetails: symbol=${searchContract.symbol()} secType=${searchContract.secType()} currency=${searchContract.currency()} expiry=${searchContract.lastTradeDateOrContractMonth()} right=${searchContract.right()}"
        }
        client.reqContractDetails(reqId, searchContract)

        val details: List<com.ib.client.ContractDetails> =
            try {
                withTimeout(5000L) {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                registry.pendingContractDetails.remove(reqId)
                registry.timedOutReqIds.add(reqId)
                val msg = "Option contract lookup timeout for $key after 5s"
                logger.error { "[$key] Option contract lookup timeout (5s) — IBKR not responding" }
                inFlightOptionConIds.remove(key)
                resultDeferred.completeExceptionally(IllegalStateException(msg))
                error(msg)
            }
        logger.debug { "[$key] reqContractDetails returned ${details.size} contracts" }

        // Cache the authoritative strike list for this expiry+right from the real IBKR response.
        // reqSecDefOptParams returns a flat union across all expirations; far-out monthly expiries have
        // coarser strike spacing (e.g. $10 increments) than weeklies ($2.50/$5). Storing the real set
        // here lets the option chain adapter use only genuine strikes when building candidate lists,
        // eliminating the repeated "strike not found" failures caused by phantom near-term strikes.
        val actualStrikesForExpiry = details.map { it.contract().strike() }.toSet()
        verifiedStrikes[VerifiedKey(key.symbol, key.expiry, key.optionType)] =
            actualStrikesForExpiry.mapTo(TreeSet()) { BigDecimal(it.toString()) }
        logger.debug { "[$key] Cached ${actualStrikesForExpiry.size} verified strikes for ${key.expiry}" }

        val cachedStrikesForExpiry = optionParamsCache.getCached(key.symbol)?.strikesByExpiry?.get(key.expiry)
        cachedStrikesForExpiry?.forEach { cachedStrike ->
            if (actualStrikesForExpiry.none { strikesEqual(it, cachedStrike.toDouble()) }) {
                missingContracts[OptionContractKey(key.symbol, key.expiry, cachedStrike, key.optionType)] = Instant.now()
            }
        }

        val conId =
            details
                .filter { strikesEqual(it.contract().strike(), key.strike.toDouble()) }
                .let { matching ->
                    // Prefer the primary series (matching exchange and tradingClass from secDefOptParams)
                    matching.firstOrNull { d ->
                        (cachedExchange == null || d.contract().exchange() == cachedExchange) &&
                            (cachedTradingClass.isNullOrBlank() || d.contract().tradingClass() == cachedTradingClass)
                    } ?: matching.firstOrNull()
                }?.contract()
                ?.conid()
                ?: run {
                    val available = details.map { it.contract().strike() }.toSortedSet()
                    logger.warn { "[$key] Strike not found. Available strikes for this expiry: $available" }
                    missingContracts[key] = Instant.now()
                    val msg = "No option contract found for $key (got ${details.size} results)"
                    inFlightOptionConIds.remove(key)
                    resultDeferred.completeExceptionally(IllegalStateException(msg))
                    error(msg)
                }

        optionConIds[key] = conId
        inFlightOptionConIds.remove(key)
        resultDeferred.complete(conId)
        logger.debug { "[$key] conId = $conId" }
        return conId
    }

    fun isMissing(key: OptionContractKey): Boolean =
        missingContracts[key]?.let { Duration.between(it, Instant.now()) < missingContractTtl } ?: false

    private fun strikesEqual(
        a: Double,
        b: Double,
    ): Boolean = abs(a - b) < strikeEpsilon

    fun getCachedOptionConId(key: OptionContractKey): Int? = optionConIds[key]

    /** Returns the authoritative strike set for a given expiry+right, populated after the first
     *  reqContractDetails call for that expiry. Null means no data yet — fall back to option params. */
    fun getVerifiedStrikes(
        symbol: Symbol,
        expiry: LocalDate,
        optionType: OptionType,
    ): Set<BigDecimal>? = verifiedStrikes[VerifiedKey(symbol, expiry, optionType)]

    private fun evictExpired() {
        val today = LocalDate.now()
        optionConIds.keys.removeIf { it.expiry.isBefore(today) }
        missingContracts.keys.removeIf { it.expiry.isBefore(today) }
        verifiedStrikes.keys.removeIf { it.expiry.isBefore(today) }
    }
}
