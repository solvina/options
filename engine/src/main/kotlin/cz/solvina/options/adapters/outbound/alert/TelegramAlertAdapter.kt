package cz.solvina.options.adapters.outbound.alert

import cz.solvina.options.domain.features.alert.AlertLevel
import cz.solvina.options.domain.features.alert.AlertPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Sends alerts to Telegram via the Bot API. No-op when disabled or unconfigured.
 *
 * SECURITY: the bot token appears in the request URL (Telegram's API design). This adapter
 * never logs the URL or token — only the alert level/title — to avoid the token leaking into
 * journald (see TELEGRAM_BOT.md note about httpx leaking token URLs).
 *
 * PACING + COALESCING: [send] never posts inline. It enqueues onto [queue]; a single drainer
 * coroutine posts at most one message per [MIN_INTERVAL_MS] (Telegram's per-chat ceiling is
 * ~1 msg/s) and honours HTTP 429. When a burst is already queued — the real-world case is a
 * session-wide market-data outage firing one CRITICAL per symbol (30+ at once, which previously
 * 429'd every message and lost them all) — the drainer collapses the backlog into a single
 * summary message instead of one per symbol. A lone alert is delivered unchanged.
 */
@Component
class TelegramAlertAdapter(
    private val config: TelegramAlertConfig,
) : AlertPort {
    private data class Alert(
        val level: AlertLevel,
        val title: String,
        val body: String,
    )

    private val http: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    // Unlimited so an enqueue never blocks or drops a caller; a burst is bounded by the scan size
    // and drained (coalesced) faster than it can grow.
    private val queue = Channel<Alert>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch { drain() }
    }

    override suspend fun send(
        level: AlertLevel,
        title: String,
        body: String,
    ) {
        if (!config.enabled || config.botToken.isBlank() || config.chatId.isBlank()) {
            logger.debug { "Alert suppressed (telegram disabled/unconfigured): [$level] $title" }
            return
        }
        // Non-blocking hand-off; the drainer owns pacing and coalescing.
        queue.trySend(Alert(level, title, body))
    }

    private suspend fun drain() {
        for (first in queue) {
            // Absorb everything already queued at this instant — a fleet-wide outage enqueues its
            // whole burst before the first drain tick, so this collapses it into one send.
            val batch = mutableListOf(first)
            while (batch.size < MAX_BATCH) {
                val next = queue.tryReceive().getOrNull() ?: break
                batch.add(next)
            }

            val (title, text) =
                if (batch.size == 1) {
                    val a = batch.first()
                    a.title to "${a.level.emoji} ${a.title}\n\n${a.body}"
                } else {
                    coalesce(batch)
                }
            postWith429Retry(title, text)

            // Space out sends so a drained backlog still respects Telegram's per-chat rate.
            delay(MIN_INTERVAL_MS)
        }
    }

    /** Fold a burst into one message: highest level's emoji, a count, then a truncated title list. */
    private fun coalesce(batch: List<Alert>): Pair<String, String> {
        val top = batch.maxByOrNull { it.level.ordinal }?.level ?: AlertLevel.INFO
        val byLevel = batch.groupingBy { it.level }.eachCount()
        val breakdown =
            AlertLevel.entries
                .filter { byLevel[it] != null }
                .joinToString(", ") { "${byLevel[it]}× ${it.name}" }
        val title = "${batch.size} alerts ($breakdown)"
        val shown = batch.take(MAX_LISTED)
        val lines = shown.joinToString("\n") { "• ${it.level.emoji} ${it.title}" }
        val more = if (batch.size > shown.size) "\n… +${batch.size - shown.size} more" else ""
        val text = "${top.emoji} $title\n\n$lines$more"
        return title to text
    }

    /** POST once; on HTTP 429 wait a fixed backoff and retry a single time. */
    private suspend fun postWith429Retry(
        title: String,
        text: String,
    ) {
        val status = post(title, text)
        if (status == 429) {
            logger.warn { "Telegram 429 for '$title' — retrying after ${RETRY_AFTER_DEFAULT_MS}ms" }
            delay(RETRY_AFTER_DEFAULT_MS)
            post(title, text)
        }
    }

    /** Returns the HTTP status code, or null when the request threw. */
    private suspend fun post(
        title: String,
        text: String,
    ): Int? {
        val threadField =
            config.messageThreadId
                .trim()
                .takeIf { it.matches(Regex("-?\\d+")) }
                ?.let { ""","message_thread_id":$it""" }
                ?: ""
        val payload =
            """{"chat_id":${jsonString(config.chatId)}$threadField,"text":${jsonString(text)},"disable_web_page_preview":true}"""

        return try {
            withContext(Dispatchers.IO) {
                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create("https://api.telegram.org/bot${config.botToken}/sendMessage"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    // Body may echo the request but never the token; safe to log.
                    logger.warn { "Telegram alert '$title' failed: HTTP ${response.statusCode()} ${response.body()}" }
                }
                response.statusCode()
            }
        } catch (e: Exception) {
            logger.warn { "Telegram alert '$title' failed: ${e.message}" }
            null
        }
    }

    /** Minimal JSON string escaping (quotes, backslash, control chars). */
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    private companion object {
        /** Telegram's per-chat ceiling is ~1 msg/s; space drained sends just above it. */
        const val MIN_INTERVAL_MS = 1_100L

        /** Cap on how many queued alerts one drain cycle folds together. */
        const val MAX_BATCH = 100

        /** How many titles a coalesced message lists before "+N more". */
        const val MAX_LISTED = 15

        /** Backoff before the single 429 retry. */
        const val RETRY_AFTER_DEFAULT_MS = 5_000L
    }
}
