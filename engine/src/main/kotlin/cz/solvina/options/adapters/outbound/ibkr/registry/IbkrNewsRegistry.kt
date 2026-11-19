package cz.solvina.options.adapters.outbound.ibkr.registry

import com.ib.client.NewsProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

/** One historical-news row as IBKR sends it (time is "yyyy-MM-dd HH:mm:ss.0", UTC). */
data class IbkrNewsRow(
    val time: String,
    val providerCode: String,
    val articleId: String,
    val headline: String,
)

data class IbkrArticle(
    val type: Int,
    val text: String,
)

/** Pending news requests (providers, historical headlines, articles), keyed by request id. */
@Component
class IbkrNewsRegistry {
    @Volatile
    private var providersAnswer: CompletableDeferred<List<NewsProvider>> = CompletableDeferred()

    /** The current providers answer (await after [startProviders]). */
    fun providers(): CompletableDeferred<List<NewsProvider>> = providersAnswer

    private class Headlines {
        val rows = CopyOnWriteArrayList<IbkrNewsRow>()
        val done = CompletableDeferred<List<IbkrNewsRow>>()
    }

    private val headlines = ConcurrentHashMap<Int, Headlines>()
    private val articles = ConcurrentHashMap<Int, CompletableDeferred<IbkrArticle>>()

    private var providersRequested = false

    /** True when the caller should send a providers request (none answered or in flight). */
    @Synchronized
    fun startProviders(): Boolean {
        val usable = !providersAnswer.isCompleted || providersAnswer.getCompletionExceptionOrNull() == null
        if (providersRequested && usable) return false
        if (providersAnswer.isCompleted) providersAnswer = CompletableDeferred()
        providersRequested = true
        return true
    }

    fun onProviders(list: List<NewsProvider>) {
        providersAnswer.complete(list)
    }

    fun startHeadlines(reqId: Int): CompletableDeferred<List<IbkrNewsRow>> = Headlines().also { headlines[reqId] = it }.done

    fun onHeadline(
        reqId: Int,
        row: IbkrNewsRow,
    ) {
        headlines[reqId]?.rows?.add(row)
    }

    fun onHeadlinesEnd(reqId: Int) {
        headlines.remove(reqId)?.let { it.done.complete(it.rows.toList()) }
    }

    fun startArticle(reqId: Int): CompletableDeferred<IbkrArticle> = CompletableDeferred<IbkrArticle>().also { articles[reqId] = it }

    fun onArticle(
        reqId: Int,
        type: Int,
        text: String,
    ) {
        articles.remove(reqId)?.complete(IbkrArticle(type, text))
    }

    /** Forget a request the caller gave up on. */
    fun abandon(reqId: Int) {
        headlines.remove(reqId)
        articles.remove(reqId)
    }

    fun onError(
        reqId: Int,
        code: Int,
        message: String,
    ) {
        val ex = RuntimeException("IBKR news request $reqId failed: $code $message")
        headlines.remove(reqId)?.done?.completeExceptionally(ex)
        articles.remove(reqId)?.completeExceptionally(ex)
    }

    @Synchronized
    fun cancelAllPending(cause: Exception) {
        val ex = RuntimeException(cause.message ?: "IBKR news request cancelled", cause)
        val pending = headlines.size + articles.size
        if (pending > 0) logger.warn { "Cancelling $pending pending news requests due to disconnect" }
        headlines.values.forEach { it.done.completeExceptionally(ex) }
        articles.values.forEach { it.completeExceptionally(ex) }
        headlines.clear()
        articles.clear()
        if (!providersAnswer.isCompleted) providersAnswer.completeExceptionally(ex)
        providersRequested = false
    }
}
