package cz.solvina.options.adapters.outbound.earnings

import com.fasterxml.jackson.databind.ObjectMapper
import cz.solvina.options.domain.features.universe.EarningsDataPort
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val logger = KotlinLogging.logger {}

/**
 * Next-earnings dates from the public Nasdaq analyst API
 * (`https://api.nasdaq.com/api/analyst/{symbol}/earnings-date`, Zacks data — the same source the
 * nasdaq.com quote page renders). No authentication; requires browser-ish headers or the edge
 * returns 403. Verified reachable from this deployment 2026-07-18.
 *
 * The date appears in two places; both are parsed, first match wins:
 *  - `data.announcement`: "Earnings announcement* for AAPL: Jul 30, 2026"
 *  - `data.reportText`:   "... is expected* to report earnings on  07/30/2026 ..."
 *
 * Index/sector ETFs (SPY, XLE, …) have no earnings — the API returns an empty payload and this
 * adapter returns null, which leaves the universe row untouched.
 */
@Component
class NasdaqEarningsDateAdapter(
    private val objectMapper: ObjectMapper,
) : EarningsDataPort {
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    override suspend fun fetchNextEarningsDate(symbol: Symbol): LocalDate? =
        withContext(Dispatchers.IO) {
            runCatching { fetch(symbol) }
                .onFailure { e -> logger.warn { "[$symbol] Nasdaq earnings-date fetch failed: ${e.message}" } }
                .getOrNull()
        }

    private fun fetch(symbol: Symbol): LocalDate? {
        val request =
            HttpRequest
                .newBuilder(URI.create("https://api.nasdaq.com/api/analyst/${symbol.value}/earnings-date"))
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            logger.warn { "[$symbol] Nasdaq earnings-date HTTP ${response.statusCode()}" }
            return null
        }
        val data = objectMapper.readTree(response.body()).path("data")
        if (data.isMissingNode || data.isNull) return null

        parseAnnouncement(data.path("announcement").asText(""))?.let { return it }
        return parseReportText(data.path("reportText").asText(""))
    }

    /** "Earnings announcement* for AAPL: Jul 30, 2026" → 2026-07-30. */
    private fun parseAnnouncement(text: String): LocalDate? {
        val datePart = text.substringAfterLast(": ", "").trim()
        if (datePart.isEmpty()) return null
        return runCatching { LocalDate.parse(datePart, ANNOUNCEMENT_FORMAT) }.getOrNull()
    }

    /** First MM/dd/yyyy occurrence, e.g. "… report earnings on  07/30/2026 after market close". */
    private fun parseReportText(text: String): LocalDate? {
        val match = REPORT_DATE.find(text)?.value ?: return null
        return runCatching { LocalDate.parse(match, REPORT_FORMAT) }.getOrNull()
    }

    companion object {
        private val ANNOUNCEMENT_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
        private val REPORT_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH)
        private val REPORT_DATE = Regex("""\d{2}/\d{2}/\d{4}""")
    }
}
