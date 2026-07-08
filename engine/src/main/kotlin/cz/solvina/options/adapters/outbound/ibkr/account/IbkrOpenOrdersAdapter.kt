package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.EClientSocket
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrOpenOrdersAdapter(
    private val client: EClientSocket,
    private val registry: IbkrOpenOrdersRegistry,
) {
    // The registry holds a SINGLE shared pending-deferred + buffer, so two overlapping requests would
    // cancel and clear each other (startRequest cancels the in-flight deferred), corrupting results and
    // producing spurious "order still present" verdicts. Serialize so each request/response round-trip
    // owns that shared state exclusively. Repricing fires many verification calls concurrently, so this
    // is a hot path — but each round-trip is short and this removes a large source of false Unverified.
    private val mutex = Mutex()

    suspend fun getOpenOrders(): List<OpenOrder> =
        mutex.withLock {
            val deferred = registry.startRequest()
            client.reqAllOpenOrders()
            logger.debug { "reqAllOpenOrders sent" }
            withTimeout(10_000L) { deferred.await() }
        }
}
