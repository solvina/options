package cz.solvina.options.adapters.outbound.ibkr.market

import com.ib.client.EClientSocket
import cz.solvina.options.adapters.outbound.ibkr.cache.IbkrContractCache
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrNewsRegistry
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrNewsRow
import cz.solvina.options.adapters.outbound.ibkr.registry.IbkrOrderIdCounter
import cz.solvina.options.domain.features.news.NewsConfig
import cz.solvina.options.domain.features.news.NewsHeadline
import cz.solvina.options.domain.features.news.NewsPort
import cz.solvina.options.domain.features.news.NewsProvider
import cz.solvina.options.domain.models.Symbol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

private val IBKR_NEWS_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** Leading metadata IBKR puts on some headlines, e.g. "{A:800015:L:en:K:n/a:C:0.86}". */
private val HEADLINE_META = Regex("^\\{[^}]*}\\s*")
private val HTML_TAG = Regex("<[^>]+>")
private val WHITESPACE = Regex("\\s+")

/**
 * IBKR news (AI review D2b): reqNewsProviders once (logged, so what the account really gets is on
 * record), reqHistoricalNews per symbol, reqNewsArticle for a few bodies. Free providers only:
 * nothing here subscribes to anything.
 */
@Component
class IbkrNewsAdapter(
    private val client: EClientSocket,
    private val registry: IbkrNewsRegistry,
    private val idCounter: IbkrOrderIdCounter,
    private val contractCache: IbkrContractCache,
    private val config: NewsConfig,
) : NewsPort {
    override suspend fun providers(): List<NewsProvider> {
        if (registry.startProviders()) client.reqNewsProviders()
        val list = withTimeout(10.seconds) { registry.providers().await() }
        return list.map { NewsProvider(it.providerCode(), it.providerName()) }.also { found ->
            if (loggedProviders != found) {
                loggedProviders = found
                logger.info { "IBKR news providers: ${found.joinToString { "${it.code} (${it.name})" }.ifEmpty { "none" }}" }
            }
        }
    }

    @Volatile
    private var loggedProviders: List<NewsProvider>? = null

    override suspend fun headlines(
        symbol: Symbol,
        from: Instant,
        max: Int,
    ): List<NewsHeadline> {
        val codes = config.providers.ifBlank { providers().joinToString("+") { it.code } }
        if (codes.isBlank()) return emptyList()
        val conId = contractCache.getOrFetchUnderlyingConId(symbol)
        val reqId = idCounter.nextOrderId()
        val done = registry.startHeadlines(reqId)
        val start = LocalDateTime.ofInstant(from, ZoneOffset.UTC).format(IBKR_NEWS_TIME) + ".0"
        client.reqHistoricalNews(reqId, conId, codes, start, "", max.coerceIn(1, 300), null)
        val rows =
            try {
                withTimeout(15.seconds) { done.await() }
            } finally {
                registry.abandon(reqId)
            }
        return rows.mapNotNull { it.toHeadline() }.sortedByDescending { it.time }.take(max)
    }

    override suspend fun article(
        provider: String,
        articleId: String,
    ): String? {
        val reqId = idCounter.nextOrderId()
        val done = registry.startArticle(reqId)
        client.reqNewsArticle(reqId, provider, articleId, null)
        val a =
            try {
                withTimeout(15.seconds) { done.await() }
            } finally {
                registry.abandon(reqId)
            }
        if (a.type != 0) return null // 1 = binary (PDF, base64)
        return a.text
            .replace(HTML_TAG, " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(WHITESPACE, " ")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun IbkrNewsRow.toHeadline(): NewsHeadline? {
        val t =
            runCatching { LocalDateTime.parse(time.substringBefore('.'), IBKR_NEWS_TIME).toInstant(ZoneOffset.UTC) }
                .onFailure { logger.debug { "Unparsed IBKR news time '$time'" } }
                .getOrNull() ?: return null
        return NewsHeadline(t, providerCode, articleId, headline.replace(HEADLINE_META, "").trim())
    }
}
