package cz.solvina.options.adapters.outbound.ibkr.registry

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
class IbkrIdCounter {
    private val counter = AtomicInteger(1)

    fun next(): Int = counter.getAndIncrement()
}
