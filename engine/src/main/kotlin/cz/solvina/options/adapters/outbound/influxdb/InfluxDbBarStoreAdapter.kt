package cz.solvina.options.adapters.outbound.influxdb

import com.influxdb.client.domain.WritePrecision
import com.influxdb.client.kotlin.InfluxDBClientKotlin
import com.influxdb.client.write.Point
import com.influxdb.query.FluxRecord
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.features.bars.SeriesSummary
import cz.solvina.options.domain.features.bars.Timeframe
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.consumeEach
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val logger = KotlinLogging.logger {}

private const val MEASUREMENT = "candle"

// readBars result cache: a parameter sweep re-reads the exact same (symbol, range, timeframe)
// series thousands of times — the Influx round-trip + record parsing dominated sweep throughput.
// Exact-key LRU, capped by entries AND total bars (a 5-min decade is ~400k bars ≈ 25 MB), with a
// short TTL because replicated/backfilled writes can land in an already-cached range from outside
// this adapter (EDR); local writes through the adapter evict matching series immediately.
private const val CACHE_MAX_ENTRIES = 10
private const val CACHE_MAX_TOTAL_BARS = 1_500_000
private const val CACHE_TTL_MS = 10 * 60 * 1000L

@Component
class InfluxDbBarStoreAdapter(
    private val client: InfluxDBClientKotlin,
    private val props: InfluxDbProperties,
) : BarStorePort {
    private data class ReadKey(
        val symbol: String,
        val from: Instant,
        val to: Instant,
        val interval: String,
    )

    private class CachedRead(
        val bars: List<FiveMinuteBar>,
        val at: Long = System.currentTimeMillis(),
    )

    private val readCache =
        object : LinkedHashMap<ReadKey, CachedRead>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<ReadKey, CachedRead>): Boolean = size > CACHE_MAX_ENTRIES
        }

    private fun cacheGet(key: ReadKey): List<FiveMinuteBar>? =
        synchronized(readCache) {
            val hit = readCache[key] ?: return null
            if (System.currentTimeMillis() - hit.at > CACHE_TTL_MS) {
                readCache.remove(key)
                null
            } else {
                hit.bars
            }
        }

    private fun cachePut(
        key: ReadKey,
        bars: List<FiveMinuteBar>,
    ) = synchronized(readCache) {
        readCache[key] = CachedRead(bars)
        while (readCache.values.sumOf { it.bars.size } > CACHE_MAX_TOTAL_BARS && readCache.size > 1) {
            val eldest = readCache.keys.first()
            readCache.remove(eldest)
        }
    }

    private fun cacheEvict(
        symbol: Symbol,
        timeframe: Timeframe,
    ) = synchronized(readCache) {
        readCache.keys.removeIf { it.symbol == symbol.value && it.interval == timeframe.label }
    }

    override suspend fun writeBar(
        symbol: Symbol,
        bar: FiveMinuteBar,
        timeframe: Timeframe,
    ) {
        try {
            client.getWriteKotlinApi().writePoint(toPoint(symbol, bar, timeframe))
            cacheEvict(symbol, timeframe)
        } catch (e: Exception) {
            logger.warn { "[${symbol.value}] InfluxDB write failed: ${e.message}" }
        }
    }

    override suspend fun writeBars(
        symbol: Symbol,
        bars: List<FiveMinuteBar>,
        timeframe: Timeframe,
    ) {
        if (bars.isEmpty()) return
        try {
            client.getWriteKotlinApi().writePoints(bars.map { toPoint(symbol, it, timeframe) })
            cacheEvict(symbol, timeframe)
        } catch (e: Exception) {
            logger.warn { "[${symbol.value}] InfluxDB bulk write failed (${bars.size} bars): ${e.message}" }
        }
    }

    override suspend fun readBars(
        symbol: Symbol,
        from: Instant,
        to: Instant,
        timeframe: Timeframe,
    ): List<FiveMinuteBar> {
        val key = ReadKey(symbol.value, from, to, timeframe.label)
        cacheGet(key)?.let { return it }
        val flux =
            """
            from(bucket: "${props.bucket}")
              |> range(start: $from, stop: $to)
              |> filter(fn: (r) => r._measurement == "$MEASUREMENT")
              |> filter(fn: (r) => r.symbol == "${symbol.value}")
              |> filter(fn: (r) => r.interval == "${timeframe.label}")
              |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
              |> sort(columns: ["_time"])
            """.trimIndent()
        return try {
            val result = mutableListOf<FiveMinuteBar>()
            client.getQueryKotlinApi().query(flux).consumeEach { record ->
                parseBar(record)?.let { result.add(it) }
            }
            cachePut(key, result)
            result
        } catch (e: Exception) {
            logger.warn { "[${symbol.value}] InfluxDB readBars failed: ${e.message}" }
            emptyList()
        }
    }

    override suspend fun lastBarTime(
        symbol: Symbol,
        timeframe: Timeframe,
    ): Instant? {
        val flux =
            """
            from(bucket: "${props.bucket}")
              |> range(start: -25y)
              |> filter(fn: (r) => r._measurement == "$MEASUREMENT")
              |> filter(fn: (r) => r.symbol == "${symbol.value}")
              |> filter(fn: (r) => r.interval == "${timeframe.label}")
              |> filter(fn: (r) => r._field == "close")
              |> last()
            """.trimIndent()
        return try {
            var result: Instant? = null
            client.getQueryKotlinApi().query(flux).consumeEach { record ->
                if (result == null) result = record.getTime()
            }
            result
        } catch (e: Exception) {
            logger.warn { "[${symbol.value}] InfluxDB lastBarTime failed: ${e.message}" }
            null
        }
    }

    /**
     * Returns bar count per UTC calendar day using aggregateWindow.
     * aggregateWindow sets _time to the window end (next midnight), so we subtract 1 day to get
     * the trading date. Days with no data are included with count 0.
     */
    override suspend fun coverageByDay(
        symbol: Symbol,
        from: LocalDate,
        to: LocalDate,
        timeframe: Timeframe,
    ): Map<LocalDate, Int> {
        val start = from.atStartOfDay(ZoneOffset.UTC).toInstant()
        val stop = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val flux =
            """
            from(bucket: "${props.bucket}")
              |> range(start: $start, stop: $stop)
              |> filter(fn: (r) => r._measurement == "$MEASUREMENT"
                   and r.symbol == "${symbol.value}"
                   and r.interval == "${timeframe.label}"
                   and r._field == "close")
              |> aggregateWindow(every: 1d, fn: count, createEmpty: true)
            """.trimIndent()
        return try {
            val result = mutableMapOf<LocalDate, Int>()
            client.getQueryKotlinApi().query(flux).consumeEach { record ->
                // _time is the end of the 1-day window; subtract 1 day to get the calendar date
                val windowEnd = record.getTime() ?: return@consumeEach
                val date = windowEnd.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                if (!date.isBefore(from) && !date.isAfter(to)) {
                    result[date] = (record.value as? Number)?.toInt() ?: 0
                }
            }
            // Ensure every day in the range has an entry
            from.datesUntil(to.plusDays(1)).forEach { day -> result.putIfAbsent(day, 0) }
            result
        } catch (e: Exception) {
            logger.warn { "[${symbol.value}] InfluxDB coverageByDay failed: ${e.message}" }
            emptyMap()
        }
    }

    override suspend fun seriesSummary(): List<SeriesSummary> {
        // Three grouped aggregates over the whole bucket (count / first / last per series),
        // merged by symbol+interval. Full-range scans, but this backs a maintenance page only.
        data class Acc(
            var count: Long = 0,
            var first: Instant? = null,
            var last: Instant? = null,
        )

        val acc = mutableMapOf<Pair<String, String>, Acc>()
        val base =
            """
            from(bucket: "${props.bucket}")
              |> range(start: 1990-01-01T00:00:00Z)
              |> filter(fn: (r) => r._measurement == "$MEASUREMENT" and r._field == "close")
              |> group(columns: ["symbol", "interval"])
            """.trimIndent()
        return try {
            client.getQueryKotlinApi().query("$base\n  |> count()").consumeEach { r ->
                acc.getOrPut(r.seriesKey()) { Acc() }.count = (r.value as? Number)?.toLong() ?: 0
            }
            client.getQueryKotlinApi().query("$base\n  |> first()").consumeEach { r ->
                acc.getOrPut(r.seriesKey()) { Acc() }.first = r.getTime()
            }
            client.getQueryKotlinApi().query("$base\n  |> last()").consumeEach { r ->
                acc.getOrPut(r.seriesKey()) { Acc() }.last = r.getTime()
            }
            acc
                .mapNotNull { (key, a) ->
                    val (symbol, interval) = key
                    val first = a.first ?: return@mapNotNull null
                    val last = a.last ?: return@mapNotNull null
                    SeriesSummary(symbol, interval, first, last, a.count)
                }.sortedWith(compareBy({ it.symbol }, { it.interval }))
        } catch (e: Exception) {
            logger.warn { "InfluxDB seriesSummary failed: ${e.message}" }
            emptyList()
        }
    }

    private fun FluxRecord.seriesKey(): Pair<String, String> =
        (values["symbol"]?.toString() ?: "?") to (values["interval"]?.toString() ?: "?")

    private fun toPoint(
        symbol: Symbol,
        bar: FiveMinuteBar,
        timeframe: Timeframe,
    ): Point =
        Point
            .measurement(MEASUREMENT)
            .addTag("symbol", symbol.value)
            .addTag("interval", timeframe.label)
            .addField("open", bar.open)
            .addField("high", bar.high)
            .addField("low", bar.low)
            .addField("close", bar.close)
            .addField("volume", bar.volume)
            .time(bar.time, WritePrecision.S)

    private fun parseBar(record: FluxRecord): FiveMinuteBar? {
        return try {
            FiveMinuteBar(
                time = record.getTime() ?: return null,
                open = record.getValueByKey("open") as? Double ?: return null,
                high = record.getValueByKey("high") as? Double ?: return null,
                low = record.getValueByKey("low") as? Double ?: return null,
                close = record.getValueByKey("close") as? Double ?: return null,
                volume =
                    when (val v = record.getValueByKey("volume")) {
                        is Long -> v
                        is Double -> v.toLong()
                        else -> return null
                    },
            )
        } catch (e: Exception) {
            null
        }
    }
}
