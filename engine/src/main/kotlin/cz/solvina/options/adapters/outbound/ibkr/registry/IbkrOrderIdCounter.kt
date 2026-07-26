package cz.solvina.options.adapters.outbound.ibkr.registry

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
class IbkrOrderIdCounter {
    private val logger = KotlinLogging.logger {}
    private val counter = AtomicInteger(-1)

    /**
     * Called exclusively by EWrapper.nextValidId() on socket connect/reconnect.
     */
    fun init(startingId: Int) {
        counter.set(startingId)
        logger.info { "Order ID counter initialized/synchronized to $startingId" }
    }

    /**
     * Returns the next broker id only after TWS has supplied nextValidId. Order submission must not
     * block a coroutine waiting for a socket callback: callers reserve nothing until this succeeds.
     */
    fun nextOrderId(): Int {
        while (true) {
            val current = counter.get()
            check(current >= 0) { "IBKR nextValidId is not available yet" }
            if (counter.compareAndSet(current, current + 1)) return current
        }
    }

    companion object {
        fun testCounter(): IbkrOrderIdCounter {
            val counter = IbkrOrderIdCounter()
            counter.init(0)
            return counter
        }
    }
}
