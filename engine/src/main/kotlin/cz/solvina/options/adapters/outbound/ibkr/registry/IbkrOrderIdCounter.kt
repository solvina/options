package cz.solvina.options.adapters.outbound.ibkr.registry

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Component
class IbkrOrderIdCounter {
    private val logger = KotlinLogging.logger {}
    private val counter = AtomicInteger(-1)

    private val initializedLatch = CountDownLatch(1)

    /**
     * Called exclusively by EWrapper.nextValidId() on socket connect/reconnect.
     */
    fun init(startingId: Int) {
        counter.set(startingId)
        initializedLatch.countDown()
        logger.info { "Order ID counter initialized/synchronized to $startingId" }
    }

    /**
     * Blocks until nextValidId is received from TWS, then provides the next ID.
     */
    fun nextOrderId(): Int {
        check(initializedLatch.await(5, TimeUnit.SECONDS)) {
            "Timed out waiting for IBKR nextValidId initialization"
        }
        return counter.getAndIncrement()
    }

    companion object {
        fun testCounter(): IbkrOrderIdCounter {
            val counter = IbkrOrderIdCounter()
            counter.init(0)
            return counter
        }
    }
}
