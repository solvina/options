package cz.solvina.options.adapters.outbound.ibkr.account

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrContractRegistry
import cz.solvina.options.domain.features.account.AccountPosition
import cz.solvina.options.domain.features.account.PositionsPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Component
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}

@Component
class IbkrPositionsAdapter(
    private val client: EClientSocket,
    private val registry: IbkrPositionsRegistry,
    private val pnlRegistry: IbkrPnlRegistry,
    private val contractRegistry: IbkrContractRegistry,
) : PositionsPort {
    private val mutex = Mutex()

    override suspend fun getPositions(): List<AccountPosition> =
        mutex.withLock {
            val deferred = registry.startRequest()
            client.reqPositions()
            logger.debug { "reqPositions sent" }
            val positions =
                try {
                    withTimeout(10_000L.milliseconds) { deferred.await() }
                } finally {
                    client.cancelPositions()
                }
            enrichWithPnl(positions)
        }

    private suspend fun enrichWithPnl(positions: List<AccountPosition>): List<AccountPosition> =
        coroutineScope {
            // Limit concurrent PnL requests to avoid exceeding IBKR message rate limits
            val semaphore = Semaphore(permits = 5)

            positions.map { pos ->
                async {
                    semaphore.withPermit {
                        val reqId = contractRegistry.nextReqId()
                        val pnlDeferred = pnlRegistry.startRequest(reqId)

                        try {
                            client.reqPnLSingle(reqId, pos.account, "", pos.conId)
                            val pnl = withTimeoutOrNull(3_000L.milliseconds) {
                                pnlDeferred.await()
                            }
                            pos.copy(unrealizedPnL = pnl)
                        } finally {
                            // ALWAYS ensure cancel is sent, even on timeout or coroutine cancellation
                            client.cancelPnLSingle(reqId)
                            pnlRegistry.cancel(reqId)
                        }
                    }
                }
            }.awaitAll()
        }}
