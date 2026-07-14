package cz.solvina.options.adapters.outbound.ibkr.registry

import com.ib.client.Bar
import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionController
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

/** Raw-bar callback for equity OHLCV requests (e.g., 5-min candles). */
internal data class PendingRawBarsRequest(
    val onBar: (Bar) -> Unit,
    val onEnd: () -> Unit,
    val onError: (Exception) -> Unit,
)

@Component
class IbkrHistoricalDataRegistry(
    private val idCounter: IbkrIdCounter,
    private val admission: IbkrAdmissionController,
) {
    internal val pendingHistoricalBars = ConcurrentHashMap<Int, PendingBarsRequest>()

    /** For equity OHLCV requests that need the full raw com.ib.client.Bar. */
    internal val pendingRawBars = ConcurrentHashMap<Int, PendingRawBarsRequest>()

    fun nextReqId(): Int = idCounter.next()

    fun onHistoricalBar(
        reqId: Int,
        bar: Bar,
    ) {
        // Raw-bar path (equity 5-min candles, etc.)
        pendingRawBars[reqId]?.let { request ->
            request.onBar(bar)
            return
        }
        // IV/analytics path
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
        pendingRawBars.remove(reqId)?.onEnd?.invoke()
    }

    fun onError(
        id: Int,
        code: Int,
        msg: String,
    ) {
        // 162 is IBKR's generic "Historical Market Data Service error message" bucket — the text
        // decides the meaning. Only an actual pacing violation should back off the historical rate
        // limiter and count as a broker limit hit; other 162s (competing session / "different IP
        // address", "query returned no data", missing permissions) are unrelated and must NOT
        // inflate the broker-limit metric or trigger a spurious back-off. 420 = message-rate pacing.
        if (code == 420 || (code == 162 && msg.contains("pacing", ignoreCase = true))) {
            admission.notePacingViolation(code)
        }
        val ex = RuntimeException("IBKR error [code=$code]: $msg")
        pendingHistoricalBars.remove(id)?.onError?.invoke(ex)
        pendingRawBars.remove(id)?.onError?.invoke(ex)
    }

    fun cancelAllPending(cause: Exception) {
        val total = pendingHistoricalBars.size + pendingRawBars.size
        if (total > 0) {
            logger.warn { "Cancelling $total pending historical requests due to disconnect" }
        }
        pendingHistoricalBars.values.forEach { it.onError(cause) }
        pendingHistoricalBars.clear()
        pendingRawBars.values.forEach { it.onError(cause) }
        pendingRawBars.clear()
    }
}
