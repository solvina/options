package cz.solvina.options.adapters.outbound.ibkr.registry

import com.ib.client.ContractDetails
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration as KotlinDuration

private val logger = KotlinLogging.logger {}

/**
 * The registry has no state for [reqId] — it was never started, or its terminal state aged past the
 * eviction TTL.
 *
 * Distinct from a request *failure* on purpose: callers that cached a reqId from an earlier pass
 * must treat this as a cache miss and re-request, not as a broker error. Conflating the two turned
 * every reuse-after-eviction into a failed symbol scan.
 */
class UnknownContractDetailsRequest(
    val reqId: Int,
) : IllegalStateException("Unknown contract details request $reqId")

sealed interface ContractDetailsRegistryUpdate {
    val id: Int

    data class Row(
        override val id: Int,
        val contractDetails: ContractDetails,
    ) : ContractDetailsRegistryUpdate

    data class End(
        override val id: Int,
        val details: List<ContractDetails>,
    ) : ContractDetailsRegistryUpdate

    data class RequestFailed(
        override val id: Int,
        val cause: RuntimeException,
    ) : ContractDetailsRegistryUpdate
}

@Component
class IbkrContractDetailsRegistry {
    private val requests = ConcurrentHashMap<Int, ContractDetailsRequestState>()

    /**
     * How long a completed/failed request stays queryable so a later pass can reuse its result.
     *
     * Must exceed the longest interval between two lookups of the same reqId, or the reuse path is
     * guaranteed to miss. At 10 minutes against a 15-minute scanner cadence it missed *every* time,
     * turning each cached reqId into one failed symbol scan. Callers now treat a miss as a
     * cache miss rather than an error, so this is no longer a correctness dependency — but keeping
     * it above the cadence is what makes the caching worth having.
     */
    private val terminalStateTtl: Duration = Duration.ofMinutes(30)
    private val updateBus = MutableSharedFlow<ContractDetailsRegistryUpdate>(replay = 4096, extraBufferCapacity = 4096)

    val rows: Flow<ContractDetailsRegistryUpdate.Row> = updateBus.filterIsInstance()
    val ends: Flow<ContractDetailsRegistryUpdate.End> = updateBus.filterIsInstance()
    val requestFailures: Flow<ContractDetailsRegistryUpdate.RequestFailed> = updateBus.filterIsInstance()

    fun startRequest(
        reqId: Int,
        descriptor: String,
    ): ContractDetailsRequestState {
        evictTerminalStates()
        return ContractDetailsRequestState(reqId = reqId, descriptor = descriptor).also {
            requests[reqId] = it
        }
    }

    fun current(reqId: Int): ContractDetailsRequestState? = requests[reqId]

    suspend fun awaitEnd(
        reqId: Int,
        timeout: KotlinDuration,
    ): List<ContractDetails> {
        val request = requireState(reqId)
        request.terminalResult { it.details.toList() }?.let { return it }
        withTimeout(timeout) { request.terminalSignal.await() }
        return requireState(reqId).terminalResult { it.details.toList() }
            ?: error("Contract details request $reqId did not reach a terminal state")
    }

    fun onContractDetails(
        reqId: Int,
        contractDetails: ContractDetails,
    ) {
        val request = requests[reqId] ?: return
        if (request.terminal) return
        request.details.add(contractDetails)
        updateBus.tryEmit(ContractDetailsRegistryUpdate.Row(reqId, contractDetails))
    }

    fun onContractDetailsEnd(reqId: Int) {
        val request = requests[reqId] ?: return
        if (request.terminal) return
        request.status = ContractRequestStatus.COMPLETED
        request.terminalAt = Instant.now()
        request.terminalSignal.complete(Unit)
        updateBus.tryEmit(ContractDetailsRegistryUpdate.End(reqId, request.details.toList()))
    }

    fun onError(
        id: Int,
        code: Int,
        msg: String,
    ) {
        val request = requests[id] ?: return
        logger.warn { "Contract resolution failed [reqId=$id code=$code flow=contractDetails]: $msg" }
        val ex = RuntimeException("IBKR error [code=$code]: $msg")
        request.markFailed(ex)
        updateBus.tryEmit(ContractDetailsRegistryUpdate.RequestFailed(id, ex))
    }

    fun cancelAllPending(cause: Exception) {
        val ex = RuntimeException(cause.message ?: "IBKR contract details request cancelled", cause)
        val active = requests.values.filter { !it.terminal }
        if (active.isNotEmpty()) logger.warn { "Cancelling ${active.size} pending contract details requests due to disconnect" }
        active.forEach {
            it.markFailed(ex)
            updateBus.tryEmit(ContractDetailsRegistryUpdate.RequestFailed(it.reqId, ex))
        }
    }

    private fun requireState(reqId: Int): ContractDetailsRequestState = current(reqId) ?: throw UnknownContractDetailsRequest(reqId)

    private fun <T> ContractDetailsRequestState.terminalResult(value: (ContractDetailsRequestState) -> T): T? =
        when (status) {
            ContractRequestStatus.ACTIVE -> null
            ContractRequestStatus.COMPLETED -> value(this)
            ContractRequestStatus.FAILED -> throw error ?: RuntimeException("Contract details request $reqId failed")
        }

    private fun ContractDetailsRequestState.markFailed(cause: RuntimeException) {
        if (terminal) return
        status = ContractRequestStatus.FAILED
        error = cause
        terminalAt = Instant.now()
        terminalSignal.complete(Unit)
    }

    private fun evictTerminalStates(now: Instant = Instant.now()) {
        requests.entries.removeIf { (_, state) -> state.terminalAt?.let { Duration.between(it, now) > terminalStateTtl } ?: false }
    }
}
