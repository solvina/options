package cz.solvina.options.backtest

import cz.solvina.options.domain.models.HistoricalBar
import cz.solvina.options.domain.models.Symbol
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Loads pre-fetched fixture CSVs from the test classpath.
 *
 * IV bars:    fixtures/iv/{SYMBOL}.csv      columns: date,iv
 * Price bars: fixtures/prices/{SYMBOL}.csv  columns: date,close
 *
 * Both return bars sorted by date ascending.
 * Run FixtureFetchTest (tag: tws) to refresh the fixture files.
 */
object FixtureLoader {
    fun loadIvBars(symbol: Symbol): List<HistoricalBar> =
        loadCsv("fixtures/iv/${symbol.value}.csv") { cols ->
            val iv = cols[1].toDoubleOrNull()
            HistoricalBar(
                date = LocalDate.parse(cols[0]),
                close = BigDecimal(cols[1].ifBlank { "0" }),
                iv = iv,
            )
        }

    fun loadPriceBars(symbol: Symbol): List<HistoricalBar> =
        loadCsv("fixtures/prices/${symbol.value}.csv") { cols ->
            HistoricalBar(
                date = LocalDate.parse(cols[0]),
                close = BigDecimal(cols[1]),
                iv = null,
            )
        }

    private fun loadCsv(
        path: String,
        parse: (List<String>) -> HistoricalBar,
    ): List<HistoricalBar> {
        val stream =
            FixtureLoader::class.java.classLoader.getResourceAsStream(path)
                ?: error(
                    "Fixture not found: $path — run ./gradlew twsTest to fetch it from TWS",
                )
        return stream
            .bufferedReader()
            .lineSequence()
            .drop(1) // header
            .filter { it.isNotBlank() }
            .map { line -> parse(line.split(",")) }
            .sortedBy { it.date }
            .toList()
    }
}
