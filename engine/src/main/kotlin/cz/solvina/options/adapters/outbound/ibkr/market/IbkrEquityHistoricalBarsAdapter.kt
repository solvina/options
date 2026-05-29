package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Bar
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrHistoricalDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingRawBarsRequest
import cz.solvina.options.domain.features.bars.EquityHistoricalBarsPort
import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}
private val ET = ZoneId.of("America/New_York")
private val BAR_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss")

@Component
class IbkrEquityHistoricalBarsAdapter(
    private val registry: IbkrHistoricalDataRegistry,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
) : EquityHistoricalBarsPort {

    override suspend fun fetch5MinBars(
        symbol: Symbol,
        days: Int,
    ): List<FiveMinuteBar> =
        callbackFlow {
            val reqId = registry.nextReqId()
            val contract = contractFactory.stockContract(symbol)

            registry.pendingRawBars[reqId] =
                PendingRawBarsRequest(
                    onBar = { bar ->
                        parseBar(bar)?.let { trySend(it) }
                    },
                    onEnd = { close() },
                    onError = { e -> close(e) },
                )

            logger.debug { "[${symbol.value}] Requesting $days-day 5-min history (reqId=$reqId)" }
            client.reqHistoricalData(
                reqId,
                contract,
                /* endDateTime */ "",
                /* durationStr */ "$days D",
                /* barSizeSetting */ "5 mins",
                /* whatToShow */ "TRADES",
                /* useRTH */ 1,
                /* formatDate */ 2,  // epoch seconds
                /* keepUpToDate */ false,
                /* chartOptions */ null,
            )

            awaitClose { registry.pendingRawBars.remove(reqId) }
        }
            .buffer(Channel.UNLIMITED)
            .toList()

    private fun parseBar(bar: Bar): FiveMinuteBar? =
        runCatching {
            // With formatDate=2, bar.time() is epoch seconds as a string
            val epochSec = bar.time().trim().toLongOrNull()
            val time: Instant = if (epochSec != null) {
                Instant.ofEpochSecond(epochSec)
            } else {
                // Fallback: "yyyyMMdd HH:mm:ss" in ET
                val ldt = LocalDateTime.parse(bar.time().trim().take(17), BAR_TIME_FORMAT)
                ldt.atZone(ET).toInstant()
            }
            FiveMinuteBar(
                time = time,
                open = bar.open(),
                high = bar.high(),
                low = bar.low(),
                close = bar.close(),
                volume = bar.volume().value().toLong(),
            )
        }.getOrElse { e ->
            logger.warn { "Failed to parse historical bar time='${bar.time()}': ${e.message}" }
            null
        }
}
