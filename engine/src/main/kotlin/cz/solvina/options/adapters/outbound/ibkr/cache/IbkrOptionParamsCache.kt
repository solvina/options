package cz.solvina.options.adapters.outbound.ibkr.cache

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrContractRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingOptionParamsRequest
import cz.solvina.options.domain.features.scanner.ScannerConfig
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
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
) {
    private val cache = ConcurrentHashMap<Symbol, OptionParams>()

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
        logger.info { "[$symbol] Cached ${params.expirations.size} expirations, ${params.strikes.size} strikes" }
        return params
    }
}
