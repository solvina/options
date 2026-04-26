package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrRequestRegistry
import cz.solvina.options.domain.features.account.AccountDetail
import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.models.Money
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.springframework.stereotype.Component
import java.math.RoundingMode

private val logger = KotlinLogging.logger {}

@Component
class IbkrAccountAdapter(
    private val client: EClientSocket,
    private val config: IbkrConnectionConfig,
    registry: IbkrRequestRegistry,
) : AccountPort {
    private val _accountDetail = MutableStateFlow<AccountDetail?>(null)
    override val accountDetail: StateFlow<AccountDetail?> = _accountDetail.asStateFlow()

    init {
        registry.setAccountCallbacks(
            onManagedAccounts = ::onManagedAccounts,
            onAccountValue = ::onAccountValue,
            onDisconnect = ::onDisconnect,
        )
    }

    fun onManagedAccounts(accountsList: String) {
        if (config.account.isEmpty()) {
            logger.warn { "No account configured (ibkr.connection.account). Skipping subscription." }
            return
        }
        val accounts = accountsList.split(",").map { it.trim() }
        if (config.account !in accounts) {
            logger.warn { "Configured account ${config.account} not found in managed list $accounts" }
            return
        }
        logger.info { "Subscribing to account updates for ${config.account}" }
        client.reqAccountUpdates(true, config.account)
    }

    fun onAccountValue(
        key: String,
        value: String,
        accountName: String,
    ) {
        if (accountName != config.account) return
        val money =
            value
                .toBigDecimalOrNull()
                ?.setScale(2, RoundingMode.HALF_UP)
                ?.let { Money(it) } ?: return
        _accountDetail.update { current ->
            val base = current ?: AccountDetail()
            when (key) {
                "NetLiquidation" -> base.copy(totalCapital = money)
                "AvailableFunds" -> base.copy(availableFunds = money)
                "UnrealizedPnL" -> base.copy(unrealizedPnL = money)
                "ExcessLiquidity" -> base.copy(excessLiquidity = money)
                else -> current
            }
        }
        logger.debug { "[${config.account}] $key updated: $value" }
    }

    fun onDisconnect() {
        if (config.account.isNotEmpty()) client.reqAccountUpdates(false, config.account)
        _accountDetail.value = null
    }
}
