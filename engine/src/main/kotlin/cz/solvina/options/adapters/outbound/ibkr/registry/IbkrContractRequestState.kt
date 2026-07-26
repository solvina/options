package cz.solvina.options.adapters.outbound.ibkr.registry

import com.ib.client.ContractDetails
import com.ib.client.PriceIncrement
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

enum class ContractRequestStatus {
    ACTIVE,
    COMPLETED,
    FAILED,
}

data class ContractDetailsRequestState(
    val reqId: Int,
    val descriptor: String,
    val startedAt: Instant = Instant.now(),
    val details: CopyOnWriteArrayList<ContractDetails> = CopyOnWriteArrayList(),
    @Volatile var status: ContractRequestStatus = ContractRequestStatus.ACTIVE,
    @Volatile var error: RuntimeException? = null,
    @Volatile var terminalAt: Instant? = null,
) {
    val terminal: Boolean get() = status != ContractRequestStatus.ACTIVE
}

data class OptionParamsRequestState(
    val reqId: Int,
    val symbol: String,
    val startedAt: Instant = Instant.now(),
    val strikesByExpiry: ConcurrentHashMap<LocalDate, CopyOnWriteArraySet<BigDecimal>> = ConcurrentHashMap(),
    @Volatile var exchange: String = "SMART",
    @Volatile var tradingClass: String = "",
    @Volatile var multiplier: String = "100",
    @Volatile var status: ContractRequestStatus = ContractRequestStatus.ACTIVE,
    @Volatile var error: RuntimeException? = null,
    @Volatile var terminalAt: Instant? = null,
) {
    val terminal: Boolean get() = status != ContractRequestStatus.ACTIVE
}

data class MarketRuleState(
    val marketRuleId: Int,
    val startedAt: Instant = Instant.now(),
    @Volatile var increments: List<PriceIncrement> = emptyList(),
    @Volatile var status: ContractRequestStatus = ContractRequestStatus.ACTIVE,
    @Volatile var error: RuntimeException? = null,
    @Volatile var terminalAt: Instant? = null,
) {
    val terminal: Boolean get() = status != ContractRequestStatus.ACTIVE
}
