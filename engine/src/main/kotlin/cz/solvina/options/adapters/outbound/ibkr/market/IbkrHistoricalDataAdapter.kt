package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionController
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrHistoricalDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingBarsRequest
import cz.solvina.options.domain.features.regime.PriceHistoryPort
import cz.solvina.options.domain.features.volatility.HistoricalDataPort
import cz.solvina.options.domain.models.HistoricalBar
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrHistoricalDataAdapter(
    private val registry: IbkrHistoricalDataRegistry,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
    private val admission: IbkrAdmissionController,
) : HistoricalDataPort,
    PriceHistoryPort {
    override fun fetchDailyBars(
        symbol: Symbol,
        days: Int,
    ): Flow<HistoricalBar> = fetchBars(symbol, days, "OPTION_IMPLIED_VOLATILITY")

    override fun fetchDailyPriceBars(
        symbol: Symbol,
        days: Int,
    ): Flow<HistoricalBar> = fetchBars(symbol, days, "TRADES")

    private fun fetchBars(
        symbol: Symbol,
        days: Int,
        whatToShow: String,
    ): Flow<HistoricalBar> =
        flow {
            // Acquire the historical permit OUTSIDE the callbackFlow and release it in a finally,
            // mirroring IbkrEquityHistoricalBarsAdapter. Acquiring inside the callbackFlow with the
            // release in awaitClose (as before) LEAKS the permit whenever the flow is cancelled or
            // times out in the window between the acquire and awaitClose registering — reqHistoricalData
            // blocks in paceMessage() there, so the race is routinely lost. A handful of leaks exhaust
            // histInFlight for good: every later fetch then parks in acquireHistorical, no
            // reqHistoricalData reaches the socket, and the engine goes blind (flag bootstrap 0 bars,
            // scanner wedged) until an engine restart. This is the IV-rank path, which bursts at
            // startup, so it leaked fast. 2026-07-23.
            val reqId = registry.nextReqId()
            admission.acquireHistorical()
            try {
                emitAll(
                    callbackFlow {
                        registry.pendingHistoricalBars[reqId] =
                            PendingBarsRequest(
                                onBar = { bar -> trySend(bar) },
                                onEnd = { close() },
                                onError = { e -> close(e) },
                            )
                        val contract = contractFactory.stockContract(symbol)
                        logger.debug { "[$symbol] Requesting $days days of $whatToShow history (reqId=$reqId)" }
                        client.reqHistoricalData(
                            reqId,
                            contract,
                            "",
                            "$days D",
                            "1 day",
                            whatToShow,
                            1,
                            1,
                            false,
                            null,
                        )
                        awaitClose { registry.pendingHistoricalBars.remove(reqId) }
                    }.buffer(Channel.UNLIMITED),
                )
            } finally {
                // Idempotent cleanup (covers a cancel before awaitClose registered), then free the
                // permit exactly once — acquireHistorical released it itself only if IT threw, and in
                // that case this finally is not reached, so there is no double release.
                registry.pendingHistoricalBars.remove(reqId)
                admission.releaseHistorical()
            }
        }
}
