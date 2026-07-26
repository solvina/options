package cz.solvina.options.adapters.outbound.ibkr.registry

import cz.solvina.options.adapters.outbound.ibkr.cache.OptionParams
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.time.Duration as KotlinDuration

private val logger = KotlinLogging.logger {}

sealed interface OptionParamsRegistryUpdate {
    val id: Int

    data class Row(
        override val id: Int,
        val exchange: String,
        val tradingClass: String,
        val multiplier: String,
    ) : OptionParamsRegistryUpdate

    data class End(
        override val id: Int,
        val params: OptionParams,
    ) : OptionParamsRegistryUpdate

    data class RequestFailed(
        override val id: Int,
        val cause: RuntimeException,
    ) : OptionParamsRegistryUpdate
}

@Component
class IbkrOptionParamsRegistry {
    private val requests = ConcurrentHashMap<Int, OptionParamsRequestState>()
    private val terminalStateTtl: Duration = Duration.ofMinutes(10)
    private val updateBus = MutableSharedFlow<OptionParamsRegistryUpdate>(replay = 4096, extraBufferCapacity = 4096)

    val rows: Flow<OptionParamsRegistryUpdate.Row> = updateBus.filterIsInstance()
    val ends: Flow<OptionParamsRegistryUpdate.End> = updateBus.filterIsInstance()
    val requestFailures: Flow<OptionParamsRegistryUpdate.RequestFailed> = updateBus.filterIsInstance()

    fun startRequest(
        reqId: Int,
        symbol: String,
        exchange: String,
    ): OptionParamsRequestState {
        evictTerminalStates()
        return OptionParamsRequestState(reqId = reqId, symbol = symbol, exchange = exchange).also {
            requests[reqId] = it
        }
    }

    fun current(reqId: Int): OptionParamsRequestState? = requests[reqId]

    suspend fun awaitEnd(
        reqId: Int,
        timeout: KotlinDuration,
    ): OptionParams {
        current(reqId)?.terminalResult { it.toOptionParams() }?.let { return it }
        withTimeout(timeout) {
            updateBus.first {
                it.id == reqId &&
                    (it is OptionParamsRegistryUpdate.End || it is OptionParamsRegistryUpdate.RequestFailed)
            }
        }
        return requireState(reqId).terminalResult { it.toOptionParams() }
            ?: error("Option params request $reqId did not reach a terminal state")
    }

    fun onSecurityDefinitionOptionalParameter(
        reqId: Int,
        exchange: String,
        tradingClass: String,
        multiplier: String,
        expirations: Set<String>,
        strikes: Set<Double>,
    ) {
        val request = requests[reqId] ?: return
        if (request.terminal) return
        if (request.exchange == "SMART" && exchange.isNotBlank()) request.exchange = exchange
        if (request.tradingClass.isEmpty() && tradingClass.isNotBlank()) request.tradingClass = tradingClass
        if (multiplier.isNotBlank()) request.multiplier = multiplier
        if (exchange != request.exchange) return
        if (request.tradingClass.isNotEmpty() && tradingClass != request.tradingClass) return

        val parsedStrikes = strikes.filter { it > 0 }.map { BigDecimal(it.toString()) }
        expirations.forEach { expStr ->
            runCatching {
                LocalDate.parse(expStr, DateTimeFormatter.ofPattern("yyyyMMdd"))
            }.getOrNull()?.let { expiry ->
                request.strikesByExpiry.getOrPut(expiry) { CopyOnWriteArraySet() }.addAll(parsedStrikes)
            }
        }
        updateBus.tryEmit(OptionParamsRegistryUpdate.Row(reqId, request.exchange, request.tradingClass, request.multiplier))
    }

    fun onSecurityDefinitionOptionalParameterEnd(reqId: Int) {
        val request = requests[reqId] ?: return
        if (request.terminal) return
        request.status = ContractRequestStatus.COMPLETED
        request.terminalAt = Instant.now()
        updateBus.tryEmit(OptionParamsRegistryUpdate.End(reqId, request.toOptionParams()))
    }

    fun onError(
        id: Int,
        code: Int,
        msg: String,
    ) {
        val request = requests[id] ?: return
        logger.warn { "Contract resolution failed [reqId=$id code=$code flow=optionParams]: $msg" }
        val ex = RuntimeException("IBKR error [code=$code]: $msg")
        request.markFailed(ex)
        updateBus.tryEmit(OptionParamsRegistryUpdate.RequestFailed(id, ex))
    }

    fun cancelAllPending(cause: Exception) {
        val ex = RuntimeException(cause.message ?: "IBKR option params request cancelled", cause)
        val active = requests.values.filter { !it.terminal }
        if (active.isNotEmpty()) logger.warn { "Cancelling ${active.size} pending option params requests due to disconnect" }
        active.forEach {
            it.markFailed(ex)
            updateBus.tryEmit(OptionParamsRegistryUpdate.RequestFailed(it.reqId, ex))
        }
    }

    private fun requireState(reqId: Int): OptionParamsRequestState = current(reqId) ?: error("Unknown option params request $reqId")

    private fun <T> OptionParamsRequestState.terminalResult(value: (OptionParamsRequestState) -> T): T? =
        when (status) {
            ContractRequestStatus.ACTIVE -> null
            ContractRequestStatus.COMPLETED -> value(this)
            ContractRequestStatus.FAILED -> throw error ?: RuntimeException("Option params request $reqId failed")
        }

    private fun OptionParamsRequestState.toOptionParams(): OptionParams {
        val strikesByExpiry = strikesByExpiry.mapValues { it.value.toSet() }
        return OptionParams(
            expirations = strikesByExpiry.keys,
            strikes = strikesByExpiry.values.flatten().toSet(),
            strikesByExpiry = strikesByExpiry,
            fetchedAt = terminalAt ?: Instant.now(),
            exchange = exchange,
            tradingClass = tradingClass,
            multiplier = multiplier,
        )
    }

    private fun OptionParamsRequestState.markFailed(cause: RuntimeException) {
        if (terminal) return
        status = ContractRequestStatus.FAILED
        error = cause
        terminalAt = Instant.now()
    }

    private fun evictTerminalStates(now: Instant = Instant.now()) {
        requests.entries.removeIf { (_, state) -> state.terminalAt?.let { Duration.between(it, now) > terminalStateTtl } ?: false }
    }
}
