package cz.solvina.options.adapters.outbound.ibkr.registry

import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** Thrown when IBKR rejects a dividend-tick market-data request. */
class DividendTickException(
    message: String,
) : Exception(message)

/**
 * Correlates `reqMktData(genericTickList="456")` requests with the IB_DIVIDENDS tickString response
 * (field 59), which carries "trailing-12m,forward-12m,nextDividendDate,nextAmount" — the forward
 * ex-dividend date + amount over the market-data line we already subscribe to (no extra entitlement).
 */
@Component
class IbkrDividendTickRegistry(
    private val idCounter: IbkrIdCounter,
) {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()

    fun nextReqId(): Int = idCounter.next()

    fun register(reqId: Int): CompletableDeferred<String> = CompletableDeferred<String>().also { pending[reqId] = it }

    fun remove(reqId: Int) {
        pending.remove(reqId)
    }

    fun onDividendTick(
        reqId: Int,
        value: String,
    ) {
        pending.remove(reqId)?.complete(value)
    }

    /** Errors are broadcast to every registry; only completes the deferred if [reqId] is ours. */
    fun onError(
        reqId: Int,
        code: Int,
        msg: String,
    ) {
        pending.remove(reqId)?.completeExceptionally(DividendTickException("IBKR error [code=$code]: $msg"))
    }
}
