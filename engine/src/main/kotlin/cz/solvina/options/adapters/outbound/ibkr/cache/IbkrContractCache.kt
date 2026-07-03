package cz.solvina.options.adapters.outbound.ibkr.cache

import com.ib.client.EClientSocket
import com.ib.client.PriceIncrement
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.IbkrRateLimiter
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
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
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
    private val rateLimiter: IbkrRateLimiter,
    private val tradingHoursCache: cz.solvina.options.adapters.outbound.ibkr.TradingHoursCache,
) {
    @Lazy @Autowired
    private lateinit var optionParamsCache: IbkrOptionParamsCache
    private val underlyingConIds = ConcurrentHashMap<Symbol, Int>()

    // Tick metadata for the underlying stock, captured from its ContractDetails. minTick is the
    // finest increment; marketRuleIds (CSV) lets us resolve the price-banded tick (MiFID II).
    private data class StockTickMeta(
        val minTick: Double,
        val marketRuleIds: String,
    )

    private val stockTickMeta = ConcurrentHashMap<Symbol, StockTickMeta>()
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
        val details = fetchStockContractDetails(symbol)

        val conId =
            details.firstOrNull()?.contract()?.conid()
                ?: error("No stock contract found for $symbol")

        details.firstOrNull()?.let { cd ->
            stockTickMeta[symbol] = StockTickMeta(minTick = cd.minTick(), marketRuleIds = cd.marketRuleIds() ?: "")
            tradingHoursCache.update(symbol, cd.liquidHours())
        }

        underlyingConIds[symbol] = conId
        logger.debug { "[$symbol] Underlying conId = $conId" }
        return conId
    }

    /**
     * Force-refresh [symbol]'s trading calendar from a fresh stock ContractDetails fetch. Unlike
     * [getOrFetchUnderlyingConId] this always hits IBKR (the conId cache would otherwise short-circuit),
     * so a long-running engine keeps today's holiday/half-day schedule current. Best-effort: failures
     * are logged and swallowed.
     */
    suspend fun warmTradingHours(symbol: Symbol) {
        runCatching { fetchStockContractDetails(symbol) }
            .onSuccess { details -> details.firstOrNull()?.let { tradingHoursCache.update(symbol, it.liquidHours()) } }
            .onFailure { e -> logger.debug { "[$symbol] trading-hours warm failed: ${e.message}" } }
    }

    private suspend fun fetchStockContractDetails(symbol: Symbol): List<com.ib.client.ContractDetails> {
        val reqId = registry.nextReqId()
        val deferred = CompletableDeferred<List<com.ib.client.ContractDetails>>()
        registry.pendingContractDetails[reqId] = PendingContractRequest(deferred, CopyOnWriteArrayList())

        return rateLimiter.withContractDetails {
            client.reqContractDetails(reqId, contractFactory.stockContract(symbol))
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
        }
    }

    /**
     * Rounds [price] to the underlying stock's valid tick grid so order prices don't get rejected
     * with IBKR error 110 ("price does not conform to the minimum price variation"). EU equities
     * (MiFID II) have price-banded ticks, so we prefer the market-rule increment for the price band
     * and fall back to the contract's [StockTickMeta.minTick]. No-op (2dp) if no metadata is available.
     */
    suspend fun roundToTick(
        symbol: Symbol,
        price: BigDecimal,
    ): BigDecimal {
        val tick = tickSizeFor(symbol, price.toDouble())
        if (tick <= 0.0) return price.setScale(2, RoundingMode.HALF_UP)
        val tickBd = BigDecimal.valueOf(tick)
        val scale = maxOf(tickBd.stripTrailingZeros().scale(), 0)
        return price
            .divide(tickBd, 0, RoundingMode.HALF_UP)
            .multiply(tickBd)
            .setScale(scale, RoundingMode.HALF_UP)
    }

    private suspend fun tickSizeFor(
        symbol: Symbol,
        price: Double,
    ): Double {
        val meta =
            stockTickMeta[symbol] ?: run {
                runCatching { getOrFetchUnderlyingConId(symbol) } // side-effect: populates stockTickMeta
                stockTickMeta[symbol]
            } ?: return 0.0

        // Prefer the price-banded market rule (MiFID II). marketRuleIds is a CSV aligned with the
        // contract's exchanges; the first id is the primary listing's rule.
        meta.marketRuleIds
            .split(",")
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.toIntOrNull()
            ?.let { ruleId ->
                getOrFetchMarketRule(ruleId)
                    ?.filter { it.lowEdge() <= price }
                    ?.maxByOrNull { it.lowEdge() }
                    ?.increment()
                    ?.takeIf { it > 0.0 }
                    ?.let { return it }
            }

        return meta.minTick.takeIf { it > 0.0 } ?: 0.0
    }

    private suspend fun getOrFetchMarketRule(marketRuleId: Int): List<PriceIncrement>? {
        registry.cachedMarketRule(marketRuleId)?.let { return it }
        val deferred = registry.registerMarketRule(marketRuleId)
        client.reqMarketRule(marketRuleId)
        return withTimeoutOrNull(3_000L) { deferred.await() }
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
        // Use withTimeoutOrNull so we react ONLY to our own 5s timeout (returns null). An *external*
        // cancellation (e.g. a caller-side bounded wait) propagates as CancellationException and is
        // handled by the invokeOnCompletion cleanup above — it must NOT be mislabeled as an "IBKR not
        // responding" 5s timeout, and must NOT tear down the pending request while IBKR may still
        // answer. (That mislabeling was the root cause of the permanent conId-resolution failure: a
        // 100ms caller-side withTimeout cancelled this lookup, removed the pending request, and
        // discarded IBKR's ~250ms-late response, so the conId was never learned.)
        // Serialised via the rate limiter: IBKR paces concurrent contract-details for the same
        // underlying (~5s), so one lookup fires at a time.
        val details: List<com.ib.client.ContractDetails> =
            rateLimiter.withContractDetails {
                client.reqContractDetails(reqId, searchContract)
                withTimeoutOrNull(5000L) { deferred.await() }
                    ?: run {
                        registry.pendingContractDetails.remove(reqId)
                        registry.timedOutReqIds.add(reqId)
                        val msg = "Option contract lookup timeout for $key after 5s"
                        logger.error { "[$key] Option contract lookup timeout (5s) — IBKR not responding" }
                        inFlightOptionConIds.remove(key)
                        resultDeferred.completeExceptionally(IllegalStateException(msg))
                        error(msg)
                    }
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

    /**
     * Proactively resolve the authoritative (verified) strike set for an expiry+right with ONE
     * reqContractDetails, caching both the strike set AND every strike's conId. Called during the
     * scan so candidate selection only ever sees real, tradeable strikes (no phantoms), and so
     * execution finds the conId already cached (no per-entry contract-details storm / ambiguous
     * minimal-spec fallback that yields IBKR error 200 → no tick → no fill). Returns null if the
     * lookup fails/times out — the caller should skip the symbol for this cycle.
     */
    suspend fun getOrFetchVerifiedStrikes(
        symbol: Symbol,
        expiry: LocalDate,
        optionType: OptionType,
    ): Set<BigDecimal>? {
        verifiedStrikes[VerifiedKey(symbol, expiry, optionType)]?.let { return it }
        evictExpired()

        val reqId = registry.nextReqId()
        val deferred = CompletableDeferred<List<com.ib.client.ContractDetails>>()
        registry.pendingContractDetails[reqId] = PendingContractRequest(deferred, CopyOnWriteArrayList())

        val cachedParams = optionParamsCache.getCached(symbol)
        val cachedExchange = cachedParams?.exchange
        val cachedTradingClass = cachedParams?.tradingClass
        val def = contractFactory.defFor(symbol)
        val searchContract =
            com.ib.client.Contract().apply {
                symbol(symbol.value)
                secType("OPT")
                currency(def.currency)
                lastTradeDateOrContractMonth(
                    expiry.format(
                        java.time.format.DateTimeFormatter
                            .ofPattern("yyyyMMdd"),
                    ),
                )
                right(optionType.ibkrCode)
            }

        val details: List<com.ib.client.ContractDetails> =
            rateLimiter.withContractDetails {
                client.reqContractDetails(reqId, searchContract)
                withTimeoutOrNull(30000L) { deferred.await() }
                    ?: run {
                        registry.pendingContractDetails.remove(reqId)
                        registry.timedOutReqIds.add(reqId)
                        logger.warn { "[$symbol $expiry $optionType] Verified-strikes lookup timeout (30s) — skipping symbol this cycle" }
                        null
                    }
            } ?: return null

        val actualStrikes = details.map { it.contract().strike() }.toSet()
        verifiedStrikes[VerifiedKey(symbol, expiry, optionType)] =
            actualStrikes.mapTo(TreeSet()) { BigDecimal(it.toString()) }

        // Warm the per-strike conId cache (prefer the primary series) so execution resolves instantly.
        details
            .groupBy { it.contract().strike() }
            .forEach { (strike, group) ->
                val chosen =
                    group.firstOrNull { d ->
                        (cachedExchange == null || d.contract().exchange() == cachedExchange) &&
                            (cachedTradingClass.isNullOrBlank() || d.contract().tradingClass() == cachedTradingClass)
                    } ?: group.firstOrNull()
                chosen?.contract()?.conid()?.let { conId ->
                    optionConIds.putIfAbsent(
                        OptionContractKey(symbol, expiry, BigDecimal(strike.toString()), optionType),
                        conId,
                    )
                }
            }

        logger.debug { "[$symbol $expiry] Verified ${actualStrikes.size} strikes + warmed conIds (proactive)" }
        return verifiedStrikes[VerifiedKey(symbol, expiry, optionType)]
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
