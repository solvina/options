package cz.solvina.options.adapters.outbound.ibkr

import com.ib.client.Contract
import com.ib.client.EClientSocket
import com.ib.client.EReaderSignal
import com.ib.client.EWrapper
import com.ib.client.Order
import cz.solvina.options.domain.features.fatal.FatalLockoutService
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class FatalLockoutOrderRejectedException(
    message: String,
) : RuntimeException(message)

/**
 * The one choke point every outbound call takes to the broker.
 *
 * Order guard: when [FatalLockoutService] has latched (e.g. account mismatch), placeOrder drops
 * the order instead of sending it — market data, account subscriptions and cancellations keep
 * working so the operator can still see and unwind state, but nothing new reaches the market.
 *
 */
class GuardedEClientSocket(
    wrapper: EWrapper,
    signal: EReaderSignal,
    private val fatalLockout: FatalLockoutService,
) : EClientSocket(wrapper, signal) {
    override fun placeOrder(
        id: Int,
        contract: Contract,
        order: Order,
    ) {
        if (fatalLockout.isFatal) {
            val message =
                "Blocked placeOrder(id=$id, ${contract.symbol()} ${order.action()} ${order.totalQuantity()} ${contract.secType()}): " +
                    "engine is in FATAL lockout: ${fatalLockout.reasons.joinToString { it.title }}"
            logger.error {
                message
            }
            throw FatalLockoutOrderRejectedException(message)
        }
        try {
            super.placeOrder(id, contract, order)
        } catch (e: Exception) {
            logger.error(e) { "Failed to place order" }
            throw e
        }
    }
}
