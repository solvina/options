package cz.solvina.options.adapters.outbound.ibkr.registry

import com.ib.client.PriceIncrement
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration as KotlinDuration

private val logger = KotlinLogging.logger {}

sealed interface MarketRuleRegistryUpdate {
    val id: Int

    data class Received(
        override val id: Int,
        val increments: List<PriceIncrement>,
    ) : MarketRuleRegistryUpdate

    data class RequestFailed(
        override val id: Int,
        val cause: RuntimeException,
    ) : MarketRuleRegistryUpdate
}

@Component
class IbkrMarketRuleRegistry {
    private val requests = ConcurrentHashMap<Int, MarketRuleState>()
    private val terminalStateTtl: Duration = Duration.ofMinutes(10)
    private val updateBus = MutableSharedFlow<MarketRuleRegistryUpdate>(replay = 4096, extraBufferCapacity = 4096)

    val marketRules: Flow<MarketRuleRegistryUpdate.Received> = updateBus.filterIsInstance()
    val requestFailures: Flow<MarketRuleRegistryUpdate.RequestFailed> = updateBus.filterIsInstance()

    fun startRequest(marketRuleId: Int): Boolean {
        evictTerminalStates()
        while (true) {
            val existing = requests[marketRuleId]
            if (existing != null && existing.status != ContractRequestStatus.FAILED) return false
            val state = MarketRuleState(marketRuleId = marketRuleId)
            if (existing == null) {
                if (requests.putIfAbsent(marketRuleId, state) == null) return true
            } else if (requests.replace(marketRuleId, existing, state)) {
                return true
            }
        }
    }

    fun current(marketRuleId: Int): MarketRuleState? = requests[marketRuleId]

    fun cached(marketRuleId: Int): List<PriceIncrement>? =
        requests[marketRuleId]
            ?.takeIf { it.status == ContractRequestStatus.COMPLETED }
            ?.increments

    suspend fun await(
        marketRuleId: Int,
        timeout: KotlinDuration,
    ): List<PriceIncrement> {
        current(marketRuleId)?.terminalResult { it.increments }?.let { return it }
        withTimeout(timeout) {
            updateBus.first {
                it.id == marketRuleId &&
                    (it is MarketRuleRegistryUpdate.Received || it is MarketRuleRegistryUpdate.RequestFailed)
            }
        }
        return requireState(marketRuleId).terminalResult { it.increments }
            ?: error("Market rule request $marketRuleId did not reach a terminal state")
    }

    fun onMarketRule(
        marketRuleId: Int,
        increments: List<PriceIncrement>,
    ) {
        val request =
            requests.compute(marketRuleId) { _, existing ->
                (existing ?: MarketRuleState(marketRuleId = marketRuleId)).apply {
                    this.increments = increments
                    status = ContractRequestStatus.COMPLETED
                    terminalAt = Instant.now()
                    error = null
                }
            }
        updateBus.tryEmit(MarketRuleRegistryUpdate.Received(marketRuleId, request?.increments ?: increments))
    }

    fun cancelAllPending(cause: Exception) {
        val ex = RuntimeException(cause.message ?: "IBKR market rule request cancelled", cause)
        val active = requests.values.filter { !it.terminal }
        if (active.isNotEmpty()) logger.warn { "Cancelling ${active.size} pending market rule requests due to disconnect" }
        active.forEach {
            it.markFailed(ex)
            updateBus.tryEmit(MarketRuleRegistryUpdate.RequestFailed(it.marketRuleId, ex))
        }
    }

    private fun requireState(marketRuleId: Int): MarketRuleState =
        current(marketRuleId) ?: error("Unknown market rule request $marketRuleId")

    private fun <T> MarketRuleState.terminalResult(value: (MarketRuleState) -> T): T? =
        when (status) {
            ContractRequestStatus.ACTIVE -> null
            ContractRequestStatus.COMPLETED -> value(this)
            ContractRequestStatus.FAILED -> throw error ?: RuntimeException("Market rule request $marketRuleId failed")
        }

    private fun MarketRuleState.markFailed(cause: RuntimeException) {
        if (terminal) return
        status = ContractRequestStatus.FAILED
        error = cause
        terminalAt = Instant.now()
    }

    private fun evictTerminalStates(now: Instant = Instant.now()) {
        requests.entries.removeIf { (_, state) -> state.terminalAt?.let { Duration.between(it, now) > terminalStateTtl } ?: false }
    }
}
