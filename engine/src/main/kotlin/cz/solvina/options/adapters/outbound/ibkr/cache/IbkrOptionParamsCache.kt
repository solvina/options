package cz.solvina.options.adapters.outbound.ibkr.cache

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrRequestRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingOptionParamsRequest
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

private val logger = KotlinLogging.logger {}

@Component
class IbkrOptionParamsCache(
    private val registry: IbkrRequestRegistry,
    private val client: EClientSocket,
    private val contractCache: IbkrContractCache,
    private val scannerConfig: ScannerConfig,
) {
    private val cache = ConcurrentHashMap<Symbol, OptionParams>()

    suspend fun getOrFetch(symbol: Symbol): OptionParams {
        val cached = cache[symbol]
        val ttl = Duration.ofHours(scannerConfig.optionParamsCacheTtlHours)
        if (cached != null && Instant.now().isBefore(cached.fetchedAt.plus(ttl))) {
            return cached
        }

        logger.debug { "[$symbol] Fetching option params" }
        val underlyingConId = contractCache.getOrFetchUnderlyingConId(symbol)

        val reqId = registry.nextDataReqId()
        val deferred = CompletableDeferred<OptionParams>()
        registry.pendingOptionParams[reqId] =
            PendingOptionParamsRequest(
                deferred = deferred,
                expirations = CopyOnWriteArraySet(),
                strikes = CopyOnWriteArraySet(),
            )

        client.reqSecDefOptParams(reqId, symbol.value, "", "STK", underlyingConId)

        val params = deferred.await()
        cache[symbol] = params
        logger.info { "[$symbol] Cached ${params.expirations.size} expirations, ${params.strikes.size} strikes" }
        return params
    }
}
