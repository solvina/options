package cz.solvina.options.adapters.outbound.ibkr.cache

import com.ib.client.Contract
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrContractRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingContractRequest
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

@Component
class IbkrContractCache(
    private val registry: IbkrContractRegistry,
    private val client: EClientSocket,
) {
    private val underlyingConIds = ConcurrentHashMap<Symbol, Int>()
    private val optionConIds = ConcurrentHashMap<OptionContractKey, Int>()

    suspend fun getOrFetchUnderlyingConId(symbol: Symbol): Int {
        underlyingConIds[symbol]?.let { return it }

        logger.debug { "[$symbol] Fetching underlying conId" }
        val reqId = registry.nextReqId()
        val deferred = CompletableDeferred<List<com.ib.client.ContractDetails>>()
        registry.pendingContractDetails[reqId] = PendingContractRequest(deferred, CopyOnWriteArrayList())

        val contract =
            Contract().apply {
                symbol(symbol.value)
                secType("STK")
                currency("USD")
                exchange("SMART")
            }
        client.reqContractDetails(reqId, contract)

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

        val contract =
            Contract().apply {
                symbol(key.symbol.value)
                secType("OPT")
                currency("USD")
                exchange("SMART")
                lastTradeDateOrContractMonth(key.expiry.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                strike(key.strike.toDouble())
                right(key.optionType.ibkrCode)
            }
        client.reqContractDetails(reqId, contract)

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
