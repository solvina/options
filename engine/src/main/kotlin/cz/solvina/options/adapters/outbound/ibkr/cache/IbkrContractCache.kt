package cz.solvina.options.adapters.outbound.ibkr.cache

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrContractRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingContractRequest
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

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
    private val missingContracts = ConcurrentHashMap.newKeySet<OptionContractKey>()

    suspend fun getOrFetchUnderlyingConId(symbol: Symbol): Int {
        underlyingConIds[symbol]?.let { return it }

        logger.debug { "[$symbol] Fetching underlying conId" }
        val reqId = registry.nextReqId()
        val deferred = CompletableDeferred<List<com.ib.client.ContractDetails>>()
        registry.pendingContractDetails[reqId] = PendingContractRequest(deferred, CopyOnWriteArrayList())

        client.reqContractDetails(reqId, contractFactory.stockContract(symbol))

        val details = deferred.await()
        val conId =
            details.firstOrNull()?.contract()?.conid()
                ?: error("No stock contract found for $symbol")

        underlyingConIds[symbol] = conId
        logger.debug { "[$symbol] Underlying conId = $conId" }
        return conId
    }

    suspend fun getOrFetchOptionConId(key: OptionContractKey): Int {
        optionConIds[key]?.let { return it }
        if (key in missingContracts) error("No option contract found for $key (cached miss)")

        evictExpired()

        logger.debug { "[$key] Fetching option conId" }
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

        val details = deferred.await()
        logger.debug { "[$key] reqContractDetails returned ${details.size} contracts" }

        // Proactively mark all cached strikes not present in the actual results as missing for this expiry.
        // reqSecDefOptParams returns a flat union across all expirations; far-out monthly expiries have
        // coarser strike spacing (e.g. $10 increments) than weeklies ($2.50/$5). One reqContractDetails
        // call gives us the authoritative set, so we can invalidate the phantom strikes immediately instead
        // of discovering them one by one over multiple scan cycles.
        val actualStrikesForExpiry = details.map { it.contract().strike() }.toSet()
        val cachedStrikesForExpiry = optionParamsCache.getCached(key.symbol)?.strikesByExpiry?.get(key.expiry)
        cachedStrikesForExpiry?.forEach { cachedStrike ->
            if (cachedStrike.toDouble() !in actualStrikesForExpiry) {
                missingContracts.add(OptionContractKey(key.symbol, key.expiry, cachedStrike, key.optionType))
            }
        }

        val conId =
            details
                .filter { it.contract().strike() == key.strike.toDouble() }
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
                    missingContracts.add(key)
                    error("No option contract found for $key (got ${details.size} results)")
                }

        optionConIds[key] = conId
        logger.debug { "[$key] conId = $conId" }
        return conId
    }

    fun isMissing(key: OptionContractKey): Boolean = key in missingContracts

    fun getCachedOptionConId(key: OptionContractKey): Int? = optionConIds[key]

    private fun evictExpired() {
        val today = LocalDate.now()
        optionConIds.keys.removeIf { it.expiry.isBefore(today) }
        missingContracts.removeIf { it.expiry.isBefore(today) }
    }
}
