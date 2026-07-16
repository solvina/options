package cz.solvina.options.adapters.outbound.ibkr.cache

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrContractRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingOptionParamsRequest
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
class IbkrOptionParamsCache(
    private val registry: IbkrContractRegistry,
    private val client: EClientSocket,
    private val contractCache: IbkrContractCache,
    private val contractFactory: cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory,
    private val scannerConfig: ScannerConfig,
    private val store: OptionParamsStorePort,
) {
    private val cache = ConcurrentHashMap<Symbol, OptionParams>()

    /** Warm the in-memory cache from the persistent store so a restart near the open does not
     *  re-issue reqSecDefOptParams for every symbol. Mirrors the IV-rank warm-load. */
    @EventListener(ApplicationReadyEvent::class)
    fun warmCache() {
        val loaded = runCatching { store.loadAll() }.getOrElse { emptyMap() }
        loaded.forEach { (symbol, params) -> cache[symbol] = params }
        if (loaded.isNotEmpty()) logger.info { "Option-params cache warm-loaded ${loaded.size} symbols from store" }
    }

    fun getCached(symbol: Symbol): OptionParams? = cache[symbol]

    suspend fun getOrFetch(symbol: Symbol): OptionParams {
        val cached = cache[symbol]
        val ttl = Duration.ofHours(scannerConfig.optionParamsCacheTtlHours)
        if (cached != null && Instant.now().isBefore(cached.fetchedAt.plus(ttl))) {
            return cached
        }

        logger.debug { "[$symbol] Fetching option params" }
        val underlyingConId = contractCache.getOrFetchUnderlyingConId(symbol)

        val configuredExchange = contractFactory.defFor(symbol).optionExchange

        val reqId = registry.nextReqId()
        val deferred = CompletableDeferred<OptionParams>()
        registry.pendingOptionParams[reqId] =
            PendingOptionParamsRequest(
                deferred = deferred,
                exchange = configuredExchange,
            )

        client.reqSecDefOptParams(reqId, symbol.value, "", "STK", underlyingConId)

        val params = deferred.await()
        cache[symbol] = params
        if (params.expirations.isNotEmpty()) {
            runCatching { store.save(symbol, params) }
                .onFailure { logger.warn { "[$symbol] Failed to persist option params: ${it.message}" } }
        }
        logger.info {
            "[$symbol] Cached ${params.expirations.size} expirations, ${params.strikes.size} strikes " +
                "[exchange=${params.exchange} tradingClass=${params.tradingClass} multiplier=${params.multiplier}]"
        }
        if (params.expirations.isEmpty()) {
            // EU pitfall: the params request only accepts responses matching the configured
            // optionExchange — if the options actually list on a different venue (e.g. FTA vs EUREX),
            // every response is skipped and this symbol can never produce candidates.
            logger.warn {
                "[$symbol] reqSecDefOptParams yielded NO expirations for configured exchange=$configuredExchange — " +
                    "likely optionExchange mismatch (options listed on a different venue)"
            }
        }
        return params
    }
}
