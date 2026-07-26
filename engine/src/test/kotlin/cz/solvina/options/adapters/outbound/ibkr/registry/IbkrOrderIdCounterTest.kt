package cz.solvina.options.adapters.outbound.ibkr.registry

import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals

class IbkrOrderIdCounterTest {
    @Test
    fun `nextValidId reconnect never moves counter backward`() {
        val counter = IbkrOrderIdCounter()

        counter.init(100)
        assertEquals(100, counter.nextOrderId())
        assertEquals(101, counter.nextOrderId())

        counter.init(50)

        assertEquals(102, counter.nextOrderId())
    }

    @Test
    fun `init racing nextOrderId never issues a duplicate id`() {
        // A reconnect fires init() while scans draw ids at high frequency. A non-atomic init would
        // rewind the counter and reuse ids; every issued id must stay unique.
        val counter = IbkrOrderIdCounter()
        counter.init(0)
        val issued = ConcurrentHashMap.newKeySet<Int>()
        val duplicates = ConcurrentHashMap.newKeySet<Int>()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val drawsPerThread = 5_000

        repeat(6) {
            pool.submit {
                start.await()
                repeat(drawsPerThread) {
                    val id = counter.nextOrderId()
                    if (!issued.add(id)) duplicates.add(id)
                }
            }
        }
        // Hammer init() with stale/low starting ids concurrently — these must never clobber increments.
        repeat(2) {
            pool.submit {
                start.await()
                repeat(drawsPerThread) { counter.init(it % 100) }
            }
        }

        start.countDown()
        pool.shutdown()
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(emptySet(), duplicates, "counter issued duplicate order ids under concurrent init()")
        assertEquals(6 * drawsPerThread, issued.size)
    }
}
