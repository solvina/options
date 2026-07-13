package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Bar
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionController
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}
private val ET = ZoneId.of("America/New_York")
private val BAR_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss")

// IBKR endDateTime format: "yyyyMMdd HH:mm:ss UTC"
private val END_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

// Max days per IBKR request for 5-min bars
private const val MAX_CHUNK_DAYS = 59L

@Component
class IbkrEquityHistoricalBarsAdapter(
    private val registry: IbkrHistoricalDataRegistry,
    private val client: EClientSocket,
    private val contractFactory: IbkrContractFactory,
    private val admission: IbkrAdmissionController,
) : EquityHistoricalBarsPort {
    override suspend fun fetch5MinBars(
        symbol: Symbol,
        days: Int,
    ): List<FiveMinuteBar> = fetchBarsRaw(symbol, endDateTime = "", durationStr = "$days D")

    override suspend fun fetch5MinBarsForRange(
        symbol: Symbol,
        from: LocalDate,
        to: LocalDate,
    ): List<FiveMinuteBar> {
        val allBars = mutableListOf<FiveMinuteBar>()
        var chunkTo = to
        while (!chunkTo.isBefore(from)) {
            val chunkFrom = maxOf(from, chunkTo.minusDays(MAX_CHUNK_DAYS - 1))
            val days = ChronoUnit.DAYS.between(chunkFrom, chunkTo) + 1
            // Use midnight UTC of the day after chunkTo as the IBKR end timestamp
            val endDt = chunkTo.plusDays(1).atStartOfDay(ZoneOffset.UTC).format(END_DATE_FORMAT)
            logger.debug { "[${symbol.value}] Fetching chunk $chunkFrom..$chunkTo (${days}d, endDt=$endDt)" }
            val bars =
                runCatching { fetchBarsRaw(symbol, endDt, "$days D") }
                    .getOrElse { e ->
                        logger.warn { "[${symbol.value}] Chunk $chunkFrom..$chunkTo failed: ${e.message}" }
                        emptyList()
                    }
            allBars.addAll(bars)
            chunkTo = chunkFrom.minusDays(1)
        }
        return allBars.sortedBy { it.time }
    }

    private suspend fun fetchBarsRaw(
        symbol: Symbol,
        endDateTime: String,
        durationStr: String,
    ): List<FiveMinuteBar> =
        callbackFlow {
            val reqId = registry.nextReqId()
            val contract = contractFactory.stockContract(symbol)
            // Rate-limited: suspends to respect IBKR's historical pacing before firing.
            admission.acquireHistorical()
            registry.pendingRawBars[reqId] =
                PendingRawBarsRequest(
                    onBar = { bar -> parseBar(bar)?.let { trySend(it) } },
                    onEnd = { close() },
                    onError = { e -> close(e) },
                )
            logger.debug { "[${symbol.value}] reqHistoricalData reqId=$reqId endDateTime='$endDateTime' duration='$durationStr'" }
            client.reqHistoricalData(
                reqId,
                contract,
                endDateTime,
                durationStr,
                // barSizeSetting
                "5 mins",
                // whatToShow
                "TRADES",
                // useRTH
                1,
                // formatDate
                2,
                // keepUpToDate
                false,
                // chartOptions
                null,
            )
            awaitClose {
                registry.pendingRawBars.remove(reqId)
                admission.releaseHistorical()
            }
        }.buffer(Channel.UNLIMITED)
            .toList()

    private fun parseBar(bar: Bar): FiveMinuteBar? =
        runCatching {
            val epochSec = bar.time().trim().toLongOrNull()
            val time: Instant =
                if (epochSec != null) {
                    Instant.ofEpochSecond(epochSec)
                } else {
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
