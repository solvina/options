package cz.solvina.options.adapters.outbound.ibkr.account

import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class IbkrPnlRegistry {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Double>>()

    fun startRequest(reqId: Int): CompletableDeferred<Double> {
        val deferred = CompletableDeferred<Double>()
        pending[reqId] = deferred
        return deferred
    }

    fun onPnlSingle(
        reqId: Int,
        unrealizedPnL: Double,
    ) {
        pending.remove(reqId)?.complete(unrealizedPnL)
    }

    fun cancel(reqId: Int) {
        pending.remove(reqId)?.cancel()
    }
}
