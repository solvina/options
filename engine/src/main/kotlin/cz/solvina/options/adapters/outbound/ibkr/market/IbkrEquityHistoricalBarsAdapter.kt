package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.Bar
import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.IbkrAdmissionController
import cz.solvina.options.adapters.outbound.ibkr.IbkrContractFactory
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrHistoricalDataRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.PendingRawBarsRequest
import cz.solvina.options.domain.features.bars.EquityHistoricalBarsPort
import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
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

// Daily+ bars arrive as a bare "yyyyMMdd" date (see parseBar).
private val DAILY_DATE = Regex("""\d{8}""")
private val DAILY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

// IBKR endDateTime format: "yyyyMMdd HH:mm:ss UTC"
private val END_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

// Per-chunk hard timeout. IBKR can silently drop a historical response (e.g. a pacing violation that
// arrives as a generic id=-1 error, never matching the pending reqId's onError) — without this the
// callbackFlow would suspend forever and stall the whole backfill. On timeout we skip the chunk.
private const val CHUNK_TIMEOUT_MS = 45_000L

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
    ): List<FiveMinuteBar> = fetchBarsRaw(symbol, endDateTime = "", durationStr = "$days D", barSize = Timeframe.FIVE_MIN.ibkrBarSize)

    override suspend fun fetch5MinBarsForRange(
        symbol: Symbol,
        from: LocalDate,
        to: LocalDate,
        timeframe: Timeframe,
        onChunk: suspend (List<FiveMinuteBar>) -> Unit,
    ): List<FiveMinuteBar> {
        val allBars = mutableListOf<FiveMinuteBar>()
        var chunkTo = to
        while (!chunkTo.isBefore(from)) {
            val chunkFrom = maxOf(from, chunkTo.minusDays(timeframe.maxChunkDays - 1))
            val days = ChronoUnit.DAYS.between(chunkFrom, chunkTo) + 1
            // Use midnight UTC of the day after chunkTo as the IBKR end timestamp
            val endDt = chunkTo.plusDays(1).atStartOfDay(ZoneOffset.UTC).format(END_DATE_FORMAT)
            // IBKR rejects "N D" durations > 365 days (error 321) — express those in years.
            val durationStr = if (days > 365) "${(days + 364) / 365} Y" else "$days D"
            logger.debug { "[${symbol.value}] Fetching ${timeframe.label} chunk $chunkFrom..$chunkTo ($durationStr, endDt=$endDt)" }
            val bars =
                runCatching { fetchBarsRaw(symbol, endDt, durationStr, timeframe.ibkrBarSize) }
                    .getOrElse { e ->
                        logger.warn { "[${symbol.value}] Chunk $chunkFrom..$chunkTo failed: ${e.message}" }
                        emptyList()
                    }
            // Persist this chunk before moving on, so a later stalled/empty chunk never discards it.
            if (bars.isNotEmpty()) {
                runCatching { onChunk(bars) }
                    .onFailure { e -> logger.warn { "[${symbol.value}] Chunk $chunkFrom..$chunkTo persist failed: ${e.message}" } }
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
        barSize: String,
    ): List<FiveMinuteBar> {
        val reqId = registry.nextReqId()
        val contract = contractFactory.stockContract(symbol)
        // Acquire OUTSIDE the flow, release in a finally that the timeout cannot skip. Both used to
        // live inside callbackFlow (acquire in the body, release in awaitClose), which leaked a
        // permit whenever CHUNK_TIMEOUT_MS fired in the window between the acquire and awaitClose
        // being registered — reqHistoricalData sits in that window and blocks in paceMessage(), so
        // the race was routinely lost. historicalMaxInFlight leaks exhausted the semaphore for good:
        // every later request then parked in acquire, timed out at exactly 45s without a single
        // reqHistoricalData reaching the socket, and wrote 0 bars until the engine restarted.
        // Keeping the acquire outside the timeout also stops a long pacing wait from eating the
        // chunk's own budget.
        admission.acquireHistorical()
        try {
            return withTimeoutOrNull(CHUNK_TIMEOUT_MS) {
                callbackFlow {
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
                        barSize,
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
                    awaitClose { registry.pendingRawBars.remove(reqId) }
                }.buffer(Channel.UNLIMITED)
                    .toList()
            } ?: run {
                logger.warn { "[${symbol.value}] Historical chunk timed out after ${CHUNK_TIMEOUT_MS}ms (endDt=$endDateTime) — skipping" }
                emptyList()
            }
        } finally {
            // Same race applies to the registry entry: awaitClose is skipped if the flow is
            // cancelled before it registers, so drop it here too (remove is idempotent).
            registry.pendingRawBars.remove(reqId)
            admission.releaseHistorical()
        }
    }

    private fun parseBar(bar: Bar): FiveMinuteBar? =
        runCatching {
            val raw = bar.time().trim()
            // IBKR returns DAILY (and larger) bars as "yyyyMMdd" even with formatDate=2 — only
            // intraday bars come as epoch seconds. An 8-digit date read as epoch put every daily
            // bar in August 1970, where no range query ever found it (backtests saw 0 bars and
            // re-downloaded the same span on every run). Stamp daily bars at the 16:00 ET close.
            val time: Instant =
                when {
                    DAILY_DATE.matches(raw) ->
                        LocalDate
                            .parse(raw, DAILY_DATE_FORMAT)
                            .atTime(16, 0)
                            .atZone(ET)
                            .toInstant()
                    raw.toLongOrNull() != null -> Instant.ofEpochSecond(raw.toLong())
                    else -> LocalDateTime.parse(raw.take(17), BAR_TIME_FORMAT).atZone(ET).toInstant()
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
