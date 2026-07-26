package cz.solvina.options.adapters.outbound.ibkr

import com.ib.client.Contract
import com.ib.client.Decimal
import com.ib.client.EJavaSignal
import com.ib.client.EWrapper
import com.ib.client.Order
import cz.solvina.options.domain.features.fatal.FatalLockoutService
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class GuardedEClientSocketTest {
    @Test
    fun `placeOrder throws when fatal lockout is active`() {
        val fatalLockout =
            FatalLockoutService(
                alertPort = mockk(relaxed = true),
                alertScope = CoroutineScope(Dispatchers.Unconfined),
            )
        fatalLockout.trigger("account mismatch", "wrong account")
        val socket = GuardedEClientSocket(mockk<EWrapper>(relaxed = true), EJavaSignal(), fatalLockout)

        assertFailsWith<FatalLockoutOrderRejectedException> {
            socket.placeOrder(
                1,
                Contract().apply {
                    symbol("AAPL")
                    secType("STK")
                },
                Order().apply {
                    action("BUY")
                    totalQuantity(Decimal.ONE)
                },
            )
        }
    }
}
