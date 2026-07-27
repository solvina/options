package cz.solvina.options.adapters.outbound.ibkr.registry

import cz.solvina.options.domain.features.bars.RealTimeBar
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class IbkrRealTimeBarsRegistry {
    private val logger = KotlinLogging.logger {}

    /** Active reqRealTimeBars subscriptions. */
    private val pendingRealTimeBars = ConcurrentHashMap<Int, PendingRealTimeBarsRequest>()

    /** reqId → symbol for subscriptions whose owning symbol is known, so the marketDataType
     *  callback (keyed only by reqId) can be attributed to a symbol. */
    private val reqIdToSymbol = ConcurrentHashMap<Int, Symbol>()

    fun getSymbolForRealTimeBar(reqId: Int): Symbol? = reqIdToSymbol[reqId]

    fun addRealTimeBarRequest(
        reqId: Int,
        symbol: Symbol,
        request: PendingRealTimeBarsRequest,
    ) {
        pendingRealTimeBars[reqId] = request
        reqIdToSymbol[reqId] = symbol
    }

    fun removeRealTimeBarRequest(reqId: Int) {
        pendingRealTimeBars.remove(reqId)
        reqIdToSymbol.remove(reqId)
    }

    fun onRealtimeBar(
        reqId: Int,
        time: Long,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Long,
        wap: Double,
    ) {
        val bar =
            RealTimeBar(
                time = Instant.ofEpochSecond(time),
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume,
                wap = wap,
            )
        pendingRealTimeBars[reqId]
            ?.onBar
            ?.invoke(bar)
            ?: logger.warn { "Received real-time bar for unknown reqId=$reqId" }
    }

    fun onError(
        id: Int,
        code: Int,
        msg: String,
    ) {
        // Real-time bars first: the graceful branches below return unconditionally and would swallow
        // a bars rejection (e.g. 354/10195 for missing live subscription), leaving the flag strategy
        // silently bar-less. The subscriber decides how loudly to surface it.
        pendingRealTimeBars[id]?.let { request ->
            request.onError(code, msg)
            return
        }
    }
}
