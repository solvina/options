package cz.solvina.options.adapters.outbound.ibkr.account

import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class IbkrPnlRegistry {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Double>>()

    fun startRequest(reqId: Int): CompletableDeferred<Double> {
        val deferred = CompletableDeferred<Double>()
        // Cancel any pre-existing deferred for this reqId to prevent leaks
        pending.put(reqId, deferred)?.cancel()
        return deferred
    }

    fun onPnlSingle(reqId: Int, unrealizedPnL: Double) {
        // Complete without removing if you want to keep the mapping,
        // OR remove and ensure client.cancelPnLSingle(reqId) is called by the caller.
        pending.remove(reqId)?.complete(unrealizedPnL)
    }

    fun onError(reqId: Int, errorCode: Int, errorMsg: String) {
        pending.remove(reqId)?.completeExceptionally(
            RuntimeException("PnL request $reqId failed ($errorCode): $errorMsg")
        )
    }

    fun cancel(reqId: Int) {
        pending.remove(reqId)?.cancel()
    }
}
