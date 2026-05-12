package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Contract
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrHistoricalDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingBarsRequest
import cz.solvina.options.domain.features.volatility.HistoricalDataPort
import cz.solvina.options.domain.models.HistoricalBar
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class IbkrHistoricalDataAdapter(
    private val registry: IbkrHistoricalDataRegistry,
    private val client: EClientSocket,
) : HistoricalDataPort {
    override fun fetchDailyBars(
        symbol: Symbol,
        days: Int,
    ): Flow<HistoricalBar> = fetchBars(symbol, days, "OPTION_IMPLIED_VOLATILITY")

    fun fetchDailyPriceBars(
        symbol: Symbol,
        days: Int,
    ): Flow<HistoricalBar> = fetchBars(symbol, days, "TRADES")

    private fun fetchBars(
        symbol: Symbol,
        days: Int,
        whatToShow: String,
    ): Flow<HistoricalBar> =
        callbackFlow {
            val reqId = registry.nextReqId()

            registry.pendingHistoricalBars[reqId] =
                PendingBarsRequest(
                    onBar = { bar -> trySend(bar) },
                    onEnd = { close() },
                    onError = { e -> close(e) },
                )

            val contract =
                Contract().apply {
                    symbol(symbol.value)
                    secType("STK")
                    currency("USD")
                    exchange("SMART")
                }

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
        }.buffer(Channel.UNLIMITED)
}
