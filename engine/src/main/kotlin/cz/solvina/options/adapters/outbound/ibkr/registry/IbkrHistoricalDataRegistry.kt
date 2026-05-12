package cz.solvina.options.adapters.outbound.ibkr.registry

import com.ib.client.Bar
import cz.solvina.options.domain.models.HistoricalBar
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

internal data class PendingBarsRequest(
    val onBar: (HistoricalBar) -> Unit,
    val onEnd: () -> Unit,
    val onError: (Exception) -> Unit,
)

@Component
class IbkrHistoricalDataRegistry(
    private val idCounter: IbkrIdCounter,
) {
    internal val pendingHistoricalBars = ConcurrentHashMap<Int, PendingBarsRequest>()

    fun nextReqId(): Int = idCounter.next()

    fun onHistoricalBar(
        reqId: Int,
        bar: Bar,
    ) {
        val request = pendingHistoricalBars[reqId] ?: return
        val date =
            runCatching {
                LocalDate.parse(bar.time().take(8), DateTimeFormatter.ofPattern("yyyyMMdd"))
            }.getOrNull() ?: return
        val iv = bar.close().takeIf { it > 0 }
        request.onBar(HistoricalBar(date = date, close = BigDecimal(bar.close().toString()), iv = iv))
    }

    fun onHistoricalDataEnd(reqId: Int) {
        pendingHistoricalBars.remove(reqId)?.onEnd?.invoke()
    }

    fun onError(
        id: Int,
        code: Int,
        msg: String,
    ) {
        pendingHistoricalBars.remove(id)?.onError?.invoke(RuntimeException("IBKR error [code=$code]: $msg"))
    }

    fun cancelAllPending(cause: Exception) {
        if (pendingHistoricalBars.isNotEmpty()) {
            logger.warn { "Cancelling ${pendingHistoricalBars.size} pending historical requests due to disconnect" }
        }
        pendingHistoricalBars.values.forEach { it.onError(cause) }
        pendingHistoricalBars.clear()
    }
}
