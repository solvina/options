package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.EClientSocket
import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.PositionsPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrPositionsAdapter(
    private val client: EClientSocket,
    private val registry: IbkrPositionsRegistry,
) : PositionsPort {
    private val mutex = Mutex()

    override suspend fun getPositions(): List<AccountPosition> =
        mutex.withLock {
            val deferred = registry.startRequest()
            client.reqPositions()
            logger.debug { "reqPositions sent" }
            try {
                withTimeout(10_000L) { deferred.await() }
            } finally {
                client.cancelPositions()
            }
        }
}
