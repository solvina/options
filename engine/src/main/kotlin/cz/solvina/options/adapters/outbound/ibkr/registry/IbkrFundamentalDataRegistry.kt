package cz.solvina.options.adapters.outbound.ibkr.registry

import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** Thrown when IBKR rejects a fundamental-data request (e.g. code 430 — no fundamentals subscription). */
class FundamentalDataException(
    message: String,
) : Exception(message)

/**
 * Correlates `reqFundamentalData` requests (by reqId) with the single XML response delivered via the
 * EWrapper `fundamentalData` callback. Same request/await pattern as the other IBKR registries.
 */
@Component
class IbkrFundamentalDataRegistry(
    private val idCounter: IbkrIdCounter,
) {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()

    fun nextReqId(): Int = idCounter.next()

    fun register(reqId: Int): CompletableDeferred<String> = CompletableDeferred<String>().also { pending[reqId] = it }

    fun remove(reqId: Int) {
        pending.remove(reqId)
    }

    fun onFundamentalData(
        reqId: Int,
        data: String,
    ) {
        pending.remove(reqId)?.complete(data)
    }

    /** Errors are broadcast to every registry; only completes the deferred if [reqId] is ours. */
    fun onError(
        reqId: Int,
        code: Int,
        msg: String,
    ) {
        pending.remove(reqId)?.completeExceptionally(FundamentalDataException("IBKR error [code=$code]: $msg"))
    }
}
