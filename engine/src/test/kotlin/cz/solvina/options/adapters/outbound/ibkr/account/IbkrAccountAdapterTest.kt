package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrConnectionConfig
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrAccountRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

class IbkrAccountAdapterTest {
    private val client = mock(EClientSocket::class.java)
    private val accountRegistry = IbkrAccountRegistry()

    private fun adapter(account: String) = IbkrAccountAdapter(client, IbkrConnectionConfig(account = account), accountRegistry)

    @Test
    fun `subscribes only to the configured account when managedAccounts lists two accounts`() {
        adapter("ACC1").onManagedAccounts("ACC1,ACC2")

        verify(client).reqAccountUpdates(true, "ACC1")
        verifyNoMoreInteractions(client)
    }

    @Test
    fun `does not subscribe when configured account is not in managed list`() {
        adapter("ACC3").onManagedAccounts("ACC1,ACC2")

        verifyNoMoreInteractions(client)
    }

    @Test
    fun `does not subscribe when no account is configured`() {
        adapter("").onManagedAccounts("ACC1,ACC2")

        verifyNoMoreInteractions(client)
    }
}
