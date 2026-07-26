package cz.solvina.options.adapters.outbound.ibkr.registry

import org.junit.jupiter.api.Test
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
}
