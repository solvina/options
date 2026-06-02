package cz.solvina.options.adapters.outbound.influxdb

import com.influxdb.client.domain.WritePrecision
import com.influxdb.client.kotlin.InfluxDBClientKotlin
import com.influxdb.client.write.Point
import com.influxdb.query.FluxRecord
import cz.solvina.options.domain.features.bars.BarStorePort
import cz.solvina.options.domain.features.bars.FiveMinuteBar
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.consumeEach
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val logger = KotlinLogging.logger {}

private const val MEASUREMENT = "candle"
private const val INTERVAL_5MIN = "5min"

@Component
class InfluxDbBarStoreAdapter(
    private val client: InfluxDBClientKotlin,
    private val props: InfluxDbProperties,
) : BarStorePort {

    override suspend fun writeBar(symbol: Symbol, bar: FiveMinuteBar) {
        try {
            client.getWriteKotlinApi().writePoint(toPoint(symbol, bar))
        } catch (e: Exception) {
            logger.warn { "[${symbol.value}] InfluxDB write failed: ${e.message}" }
        }
    }

    override suspend fun writeBars(symbol: Symbol, bars: List<FiveMinuteBar>) {
        if (bars.isEmpty()) return
        try {
            client.getWriteKotlinApi().writePoints(bars.map { toPoint(symbol, it) })
        } catch (e: Exception) {
            logger.warn { "[${symbol.value}] InfluxDB bulk write failed (${bars.size} bars): ${e.message}" }
        }
    }

    override suspend fun readBars(symbol: Symbol, from: Instant, to: Instant): List<FiveMinuteBar> {
        val flux = """
            from(bucket: "${props.bucket}")
              |> range(start: $from, stop: $to)
              |> filter(fn: (r) => r._measurement == "$MEASUREMENT")
              |> filter(fn: (r) => r.symbol == "${symbol.value}")
              |> filter(fn: (r) => r.interval == "$INTERVAL_5MIN")
              |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
              |> sort(columns: ["_time"])
        """.trimIndent()
        return try {
            val result = mutableListOf<FiveMinuteBar>()
            client.getQueryKotlinApi().query(flux).consumeEach { record ->
                parseBar(record)?.let { result.add(it) }
            }
            result
        } catch (e: Exception) {
            logger.warn { "[${symbol.value}] InfluxDB readBars failed: ${e.message}" }
            emptyList()
        }
    }

    override suspend fun lastBarTime(symbol: Symbol): Instant? {
        val flux = """
            from(bucket: "${props.bucket}")
              |> range(start: -30d)
              |> filter(fn: (r) => r._measurement == "$MEASUREMENT")
              |> filter(fn: (r) => r.symbol == "${symbol.value}")
              |> filter(fn: (r) => r.interval == "$INTERVAL_5MIN")
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
    override suspend fun coverageByDay(symbol: Symbol, from: LocalDate, to: LocalDate): Map<LocalDate, Int> {
        val start = from.atStartOfDay(ZoneOffset.UTC).toInstant()
        val stop = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val flux = """
            from(bucket: "${props.bucket}")
              |> range(start: $start, stop: $stop)
              |> filter(fn: (r) => r._measurement == "$MEASUREMENT"
                   and r.symbol == "${symbol.value}"
                   and r.interval == "$INTERVAL_5MIN"
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

    private fun toPoint(symbol: Symbol, bar: FiveMinuteBar): Point =
        Point.measurement(MEASUREMENT)
            .addTag("symbol", symbol.value)
            .addTag("interval", INTERVAL_5MIN)
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
                volume = when (val v = record.getValueByKey("volume")) {
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
