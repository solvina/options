package cz.solvina.options.adapters.outbound.ibkr.registry

import com.ib.client.ContractDetails
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionParams
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

private val logger = KotlinLogging.logger {}

internal data class PendingContractRequest(
    val deferred: CompletableDeferred<List<ContractDetails>>,
    val details: CopyOnWriteArrayList<ContractDetails>,
)

internal data class PendingOptionParamsRequest(
    val deferred: CompletableDeferred<OptionParams>,
    val expirations: CopyOnWriteArraySet<LocalDate>,
    val strikes: CopyOnWriteArraySet<BigDecimal>,
)

@Component
class IbkrContractRegistry(
    private val idCounter: IbkrIdCounter,
) {
    internal val pendingContractDetails = ConcurrentHashMap<Int, PendingContractRequest>()
    internal val pendingOptionParams = ConcurrentHashMap<Int, PendingOptionParamsRequest>()

    fun nextReqId(): Int = idCounter.next()

    fun onContractDetails(
        reqId: Int,
        contractDetails: ContractDetails,
    ) {
        pendingContractDetails[reqId]?.details?.add(contractDetails)
    }

    fun onContractDetailsEnd(reqId: Int) {
        val request = pendingContractDetails.remove(reqId) ?: return
        request.deferred.complete(request.details.toList())
    }

    fun onSecurityDefinitionOptionalParameter(
        reqId: Int,
        expirations: Set<String>,
        strikes: Set<Double>,
    ) {
        val request = pendingOptionParams[reqId] ?: return
        expirations.forEach { expStr ->
            runCatching {
                LocalDate.parse(expStr, DateTimeFormatter.ofPattern("yyyyMMdd"))
            }.getOrNull()?.let { request.expirations.add(it) }
        }
        strikes.forEach { s ->
            if (s > 0) request.strikes.add(BigDecimal(s.toString()))
        }
    }

    fun onSecurityDefinitionOptionalParameterEnd(reqId: Int) {
        val request = pendingOptionParams.remove(reqId) ?: return
        request.deferred.complete(
            OptionParams(
                expirations = request.expirations.toSet(),
                strikes = request.strikes.toSet(),
                fetchedAt = java.time.Instant.now(),
            ),
        )
    }

    fun onError(
        id: Int,
        code: Int,
        msg: String,
    ) {
        val ex = RuntimeException("IBKR error [code=$code]: $msg")
        pendingContractDetails.remove(id)?.deferred?.completeExceptionally(ex)
        pendingOptionParams.remove(id)?.deferred?.completeExceptionally(ex)
    }

    fun cancelAllPending(cause: Exception) {
        val count = pendingContractDetails.size + pendingOptionParams.size
        if (count > 0) logger.warn { "Cancelling $count pending contract requests due to disconnect" }
        pendingContractDetails.values.forEach { it.deferred.completeExceptionally(cause) }
        pendingContractDetails.clear()
        pendingOptionParams.values.forEach { it.deferred.completeExceptionally(cause) }
        pendingOptionParams.clear()
    }
}
