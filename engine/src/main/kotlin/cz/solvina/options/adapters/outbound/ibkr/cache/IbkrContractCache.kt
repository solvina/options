package cz.solvina.options.adapters.outbound.ibkr.cache

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrContractRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingContractRequest
import cz.solvina.options.domain.models.OptionContract
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
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
    private val underlyingConIds = ConcurrentHashMap<Symbol, Int>()
    private val optionConIds = ConcurrentHashMap<OptionContractKey, Int>()

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

        evictExpired()

        logger.debug { "[$key] Fetching option conId" }
        val reqId = registry.nextReqId()
        val deferred = CompletableDeferred<List<com.ib.client.ContractDetails>>()
        registry.pendingContractDetails[reqId] = PendingContractRequest(deferred, CopyOnWriteArrayList())

        val optionContract = OptionContract(key.symbol, key.expiry, key.strike, key.optionType)
        client.reqContractDetails(reqId, contractFactory.optionContract(optionContract))

        val details = deferred.await()
        val conId =
            details.firstOrNull()?.contract()?.conid()
                ?: error("No option contract found for $key")

        optionConIds[key] = conId
        return conId
    }

    private fun evictExpired() {
        val today = LocalDate.now()
        optionConIds.keys.removeIf { it.expiry.isBefore(today) }
    }
}
