package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrAccountRegistry
import cz.solvina.options.domain.features.account.AccountDetail
import cz.solvina.options.domain.features.account.AccountPort
import cz.solvina.options.domain.features.fatal.FatalLockoutService
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
    private val fatalLockout: FatalLockoutService,
    accountRegistry: IbkrAccountRegistry,
) : AccountPort {
    private val _accountDetail = MutableStateFlow<AccountDetail?>(null)
    override val accountDetail: StateFlow<AccountDetail?> = _accountDetail.asStateFlow()

    init {
        accountRegistry.setCallbacks(
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
        val accounts = accountsList.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (accounts.isNotEmpty() && config.account !in accounts) {
            // Wrong-account protection: the gateway is logged into a different account than this
            // engine is configured to trade (e.g. live gateway + paper config or vice versa).
            // Trading against the wrong account must be impossible, not a log line.
            fatalLockout.trigger(
                "IBKR account mismatch",
                "Configured account ${config.account} is not in the gateway's managed list $accounts. " +
                    "Check ibkr.connection.account vs the gateway login (paper vs live).",
            )
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
