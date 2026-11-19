package cz.solvina.options.domain.features.news

import cz.solvina.options.domain.models.Symbol
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Instant

data class NewsProvider(
    val code: String,
    val name: String,
)

data class NewsHeadline(
    val time: Instant,
    val provider: String,
    val articleId: String,
    val headline: String,
)

/**
 * Company news from the broker's news feeds (AI review D2b). Only the providers the account
 * gets for free (IBKR: Briefing.com, Dow Jones newsletters, ...); [providers] says which those are.
 */
interface NewsPort {
    suspend fun providers(): List<NewsProvider>

    /** Headlines for [symbol] in [from, now], newest first, at most [max]. */
    suspend fun headlines(
        symbol: Symbol,
        from: Instant,
        max: Int,
    ): List<NewsHeadline>

    /** The article's plain text, or null when the provider gives none (e.g. PDF only). */
    suspend fun article(
        provider: String,
        articleId: String,
    ): String?
}

@ConfigurationProperties("news")
data class NewsConfig(
    val enabled: Boolean = true,
    /** Provider codes joined by '+', e.g. "BRFG+BRFUPDN+DJNL"; empty = every provider the account has. */
    val providers: String = "",
    val lookbackDays: Long = 7,
    val maxHeadlines: Int = 25,
    /** Bodies fetched for the newest headlines (each is one more broker request). */
    val maxArticles: Int = 3,
    val maxArticleChars: Int = 1500,
    /** Budget for the whole news section of one snapshot. */
    val timeoutSeconds: Long = 20,
)
