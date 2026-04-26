package cz.solvina.options.adapters.outbound.ibkr.registry

import com.ib.client.Bar
import com.ib.client.ContractDetails
import cz.solvina.options.adapters.outbound.ibkr.cache.OptionParams
import cz.solvina.options.domain.features.order.OrderStatus
import cz.solvina.options.domain.models.HistoricalBar
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

internal data class PendingBarsRequest(
    val onBar: (HistoricalBar) -> Unit,
    val onEnd: () -> Unit,
    val onError: (Exception) -> Unit,
)

internal data class PendingContractRequest(
    val deferred: CompletableDeferred<List<ContractDetails>>,
    val details: CopyOnWriteArrayList<ContractDetails>,
)

internal data class PendingOptionParamsRequest(
    val deferred: CompletableDeferred<OptionParams>,
    val expirations: CopyOnWriteArraySet<LocalDate>,
    val strikes: CopyOnWriteArraySet<BigDecimal>,
)

internal data class PendingMarketDataRequest(
    val deferred: CompletableDeferred<MarketDataSnapshot>,
    val snapshot: MarketDataSnapshot = MarketDataSnapshot(),
)

@Component
class IbkrRequestRegistry {
    internal val pendingHistoricalBars = ConcurrentHashMap<Int, PendingBarsRequest>()
    internal val pendingContractDetails = ConcurrentHashMap<Int, PendingContractRequest>()
    internal val pendingOptionParams = ConcurrentHashMap<Int, PendingOptionParamsRequest>()
    internal val pendingMarketData = ConcurrentHashMap<Int, PendingMarketDataRequest>()
    internal val pendingOrderStatus = ConcurrentHashMap<Int, CompletableDeferred<OrderStatus>>()

    private val dataReqIdCounter = AtomicInteger(1)
    private val orderIdCounter = AtomicInteger(1)

    fun nextDataReqId(): Int = dataReqIdCounter.getAndIncrement()

    fun seedOrderId(id: Int) {
        orderIdCounter.set(id)
        logger.info { "Order ID counter seeded to $id" }
    }

    fun nextOrderId(): Int = orderIdCounter.getAndIncrement()

    // ---- Historical bars ----

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

    // ---- Contract details ----

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

    // ---- Option params ----

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

    // ---- Market data (snapshots) ----

    fun onTickPrice(
        reqId: Int,
        field: Int,
        price: Double,
    ) {
        val request = pendingMarketData[reqId] ?: return
        val s = request.snapshot
        when (field) {
            1 -> s.bid = price
            2 -> s.ask = price
            4 -> s.last = price
            9 -> s.close = price
        }
    }

    fun onTickOptionComputation(
        reqId: Int,
        field: Int,
        impliedVol: Double,
        delta: Double,
        gamma: Double,
        vega: Double,
        theta: Double,
    ) {
        val request = pendingMarketData[reqId] ?: return
        // Accept fields 10 (bid greeks), 11 (ask greeks), 12 (last greeks), 13 (model greeks)
        // Field 13 (model) is preferred but may not always arrive
        if (field !in 10..13) return
        val s = request.snapshot
        val sentinel = Double.MAX_VALUE
        if (!delta.isNaN() && delta != sentinel) s.delta = delta
        if (!impliedVol.isNaN() && impliedVol != sentinel) s.impliedVol = impliedVol
        if (!gamma.isNaN() && gamma != sentinel) s.gamma = gamma
        if (!vega.isNaN() && vega != sentinel) s.vega = vega
        if (!theta.isNaN() && theta != sentinel) s.theta = theta
    }

    fun onTickSnapshotEnd(reqId: Int) {
        val request = pendingMarketData.remove(reqId) ?: return
        request.deferred.complete(request.snapshot)
    }

    // ---- Account updates ----

    private var onManagedAccountsCb: ((String) -> Unit)? = null
    private var onAccountValueCb: ((String, String, String) -> Unit)? = null

    private var onDisconnectCb: (() -> Unit)? = null

    fun setAccountCallbacks(
        onManagedAccounts: (String) -> Unit,
        onAccountValue: (String, String, String) -> Unit,
        onDisconnect: () -> Unit,
    ) {
        onManagedAccountsCb = onManagedAccounts
        onAccountValueCb = onAccountValue
        onDisconnectCb = onDisconnect
    }

    fun onManagedAccounts(accountsList: String) {
        onManagedAccountsCb?.invoke(accountsList)
    }

    fun onAccountValue(
        key: String,
        value: String,
        accountName: String,
    ) {
        onAccountValueCb?.invoke(key, value, accountName)
    }

    // ---- Order status ----

    fun onOrderStatus(
        orderId: Int,
        status: String,
    ) {
        val deferred = pendingOrderStatus[orderId] ?: return
        when (status.lowercase()) {
            "filled" -> {
                pendingOrderStatus.remove(orderId)
                deferred.complete(OrderStatus.FILLED)
            }
            "cancelled", "inactive" -> {
                pendingOrderStatus.remove(orderId)
                deferred.complete(OrderStatus.CANCELLED)
            }
            else -> logger.debug { "Order $orderId status: $status (waiting for terminal status)" }
        }
    }

    // ---- Error handling ----

    fun onError(
        id: Int,
        code: Int,
        msg: String,
    ) {
        val ex = RuntimeException("IBKR error [code=$code]: $msg")
        pendingHistoricalBars.remove(id)?.onError?.invoke(ex)
        pendingContractDetails.remove(id)?.deferred?.completeExceptionally(ex)
        pendingOptionParams.remove(id)?.deferred?.completeExceptionally(ex)
        pendingMarketData.remove(id)?.deferred?.completeExceptionally(ex)
        pendingOrderStatus.remove(id)?.completeExceptionally(ex)
    }

    // ---- Disconnect cleanup ----

    fun cancelAllPending() {
        val cause = RuntimeException("IBKR disconnected")
        val count =
            pendingHistoricalBars.size +
                pendingContractDetails.size +
                pendingOptionParams.size +
                pendingMarketData.size +
                pendingOrderStatus.size
        if (count > 0) logger.warn { "Cancelling $count pending IBKR requests due to disconnect" }
        pendingHistoricalBars.values.forEach { it.onError(cause) }
        pendingHistoricalBars.clear()
        pendingContractDetails.values.forEach { it.deferred.completeExceptionally(cause) }
        pendingContractDetails.clear()
        pendingOptionParams.values.forEach { it.deferred.completeExceptionally(cause) }
        pendingOptionParams.clear()
        pendingMarketData.values.forEach { it.deferred.completeExceptionally(cause) }
        pendingMarketData.clear()
        pendingOrderStatus.values.forEach { it.completeExceptionally(cause) }
        pendingOrderStatus.clear()
        onDisconnectCb?.invoke()
    }
}
