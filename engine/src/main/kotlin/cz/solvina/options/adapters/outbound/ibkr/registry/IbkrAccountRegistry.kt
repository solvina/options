package cz.solvina.options.adapters.outbound.ibkr.registry

import org.springframework.stereotype.Component

@Component
class IbkrAccountRegistry {
    private var onManagedAccountsCb: ((String) -> Unit)? = null
    private var onAccountValueCb: ((String, String, String) -> Unit)? = null
    private var onDisconnectCb: (() -> Unit)? = null

    fun setCallbacks(
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

    fun onDisconnect() {
        onDisconnectCb?.invoke()
    }
}
